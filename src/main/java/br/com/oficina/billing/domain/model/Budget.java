package br.com.oficina.billing.domain.model;

import br.com.oficina.billing.domain.event.BillingEvent;
import br.com.oficina.billing.domain.exception.BillingRuleViolation;
import br.com.oficina.billing.domain.exception.BudgetNotFoundException;
import br.com.oficina.billing.domain.valueobject.ApprovalToken;
import br.com.oficina.billing.domain.valueobject.ApprovalToken.TokenHash;
import br.com.oficina.billing.domain.valueobject.Money;
import br.com.oficina.billing.domain.valueobject.SagaRef;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Orçamento de uma OS (aggregate root). Imutável depois de emitido (linhas e total); só o ciclo de decisão muda.
 *
 * <ul>
 *   <li>O total é sempre a soma das linhas: um comando com total divergente é recusado na emissão.</li>
 *   <li>Decisão só em {@code SENT} e dentro do prazo. Repetir a mesma decisão é idempotente (sem novo evento);
 *       decidir o contrário depois é erro de negócio.</li>
 *   <li>O token de aprovação é queimado na decisão: continua conferindo para repetir <i>a mesma</i> decisão
 *       (idempotência do link), mas o orçamento já não aceita outra.</li>
 * </ul>
 */
public final class Budget {

    private final UUID id;
    private final SagaRef ref;
    private final long clientId;
    private final Customer customer;
    private final List<BudgetLine> lines;
    private final Money total;
    private final TokenHash tokenHash;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final long version;

    private BudgetStatus status;
    private Instant tokenConsumedAt;
    private DecisionChannel decidedBy;
    private Instant decidedAt;
    private String voidReason;
    private final List<BillingEvent> events = new ArrayList<>();

    private Budget(Snapshot state) {
        this.id = Objects.requireNonNull(state.id());
        this.ref = Objects.requireNonNull(state.ref());
        this.clientId = state.clientId();
        this.customer = Objects.requireNonNull(state.customer());
        this.lines = List.copyOf(state.lines());
        this.total = Objects.requireNonNull(state.total());
        this.tokenHash = Objects.requireNonNull(state.tokenHash());
        this.createdAt = Objects.requireNonNull(state.createdAt());
        this.expiresAt = Objects.requireNonNull(state.expiresAt());
        this.version = state.version();
        this.status = Objects.requireNonNull(state.status());
        this.tokenConsumedAt = state.tokenConsumedAt();
        this.decidedBy = state.decidedBy();
        this.decidedAt = state.decidedAt();
        this.voidReason = state.voidReason();
    }

    /**
     * Emite o orçamento pedido pela Saga. Devolve também o token em claro, que só existe até o e-mail ser enviado.
     *
     * @throws BillingRuleViolation sem itens, total divergente, prazo de aprovação inválido
     */
    public static Issued issue(Request request, Instant now) {
        if (request.lines().isEmpty()) {
            throw new BillingRuleViolation("Orçamento sem itens");
        }
        Money computed = request.lines().stream().map(BudgetLine::subtotal).reduce(Money.ZERO, Money::plus);
        if (computed.compareTo(request.declaredTotal()) != 0) {
            throw new BillingRuleViolation("Total informado (" + request.declaredTotal()
                + ") difere da soma dos itens (" + computed + ")");
        }
        if (!computed.isPositive()) {
            throw new BillingRuleViolation("Orçamento com total zero");
        }
        if (request.approvalTtl().isNegative() || request.approvalTtl().isZero()) {
            throw new BillingRuleViolation("Prazo de aprovação inválido");
        }
        ApprovalToken token = ApprovalToken.generate();
        Budget budget = new Budget(new Snapshot(UUID.randomUUID(), request.ref(), request.clientId(), request.customer(),
            request.lines(), computed, BudgetStatus.SENT, token.hash(), null, null, null, null, now,
            now.plus(request.approvalTtl()), 0));
        budget.events.add(new BillingEvent.BudgetGenerated(budget.ref, budget.id, budget.total, budget.expiresAt));
        return new Issued(budget, token);
    }

    public static Budget restore(Snapshot state) {
        return new Budget(state);
    }

    /** Decisão do funcionário ou do cliente autenticado. @return {@code false} se a mesma decisão já valia */
    public boolean decide(Decision decision, DecisionChannel channel, Instant now) {
        BudgetStatus target = decision.outcome();
        if (status == target) {
            return false;
        }
        if (status != BudgetStatus.SENT) {
            throw new BillingRuleViolation("Orçamento " + describe(status) + " não aceita a decisão " + decision);
        }
        if (!now.isBefore(expiresAt)) {
            throw new BillingRuleViolation("Prazo de aprovação do orçamento encerrado em " + expiresAt);
        }
        status = target;
        decidedBy = channel;
        decidedAt = now;
        tokenConsumedAt = now;
        events.add(decision == Decision.APPROVE
            ? new BillingEvent.BudgetApproved(ref, id, channel)
            : new BillingEvent.BudgetRejected(ref, id, channel));
        return true;
    }

    /**
     * Decisão pelo link do e-mail. Token que não confere é tratado como orçamento inexistente (a rota pública não
     * revela se a OS existe).
     */
    public boolean decideWithToken(ApprovalToken candidate, Decision decision, Instant now) {
        if (!tokenHash.matches(candidate)) {
            throw new BudgetNotFoundException(ref.orderNumber());
        }
        return decide(decision, DecisionChannel.CLIENT_TOKEN, now);
    }

    /** Job de expiração. @return {@code true} se expirou agora */
    public boolean expireIfOverdue(Instant now) {
        if (status != BudgetStatus.SENT || now.isBefore(expiresAt)) {
            return false;
        }
        status = BudgetStatus.EXPIRED;
        events.add(new BillingEvent.BudgetExpired(ref, id));
        return true;
    }

    /**
     * Compensação da Saga. Anular de novo só repete a resposta (o OS pode reenviar o comando); o evento é emitido
     * mesmo assim para que a Saga, que espera a confirmação, avance.
     */
    public void voidBudget(String reason) {
        if (status != BudgetStatus.VOIDED) {
            status = BudgetStatus.VOIDED;
            voidReason = reason;
        }
        events.add(new BillingEvent.BudgetVoided(ref, id));
    }

    /** Reenvio da resposta de emissão (comando repetido com outro {@code messageId}): mesmo orçamento, sem e-mail. */
    public void repeatGenerated() {
        events.add(new BillingEvent.BudgetGenerated(ref, id, total, expiresAt));
    }

    public boolean ownedBy(long candidateClientId) {
        return clientId == candidateClientId;
    }

    public boolean belongsTo(UUID sagaId) {
        return ref.sagaId().equals(sagaId);
    }

    public List<BillingEvent> pullEvents() {
        List<BillingEvent> pulled = List.copyOf(events);
        events.clear();
        return pulled;
    }

    public Snapshot snapshot() {
        return new Snapshot(id, ref, clientId, customer, lines, total, status, tokenHash, tokenConsumedAt, decidedBy,
            decidedAt, voidReason, createdAt, expiresAt, version);
    }

    public UUID id() { return id; }
    public SagaRef ref() { return ref; }
    public Customer customer() { return customer; }
    public List<BudgetLine> lines() { return lines; }
    public Money total() { return total; }
    public BudgetStatus status() { return status; }
    public Instant expiresAt() { return expiresAt; }
    public DecisionChannel decidedBy() { return decidedBy; }
    public Instant decidedAt() { return decidedAt; }

    private static String describe(BudgetStatus status) {
        return switch (status) {
            case SENT -> "enviado";
            case APPROVED -> "já aprovado";
            case REJECTED -> "já recusado";
            case EXPIRED -> "expirado";
            case VOIDED -> "anulado";
        };
    }

    /** Pedido de emissão (vem do comando {@code GenerateBudget}). */
    public record Request(SagaRef ref, long clientId, Customer customer, List<BudgetLine> lines, Money declaredTotal,
                          Duration approvalTtl) {
    }

    /** Orçamento recém-emitido e o token em claro, a ser entregue ao cliente uma única vez. */
    public record Issued(Budget budget, ApprovalToken token) {
    }

    /** Estado completo para persistência (Memento): o repositório grava e reconstrói sem conhecer as regras. */
    public record Snapshot(UUID id, SagaRef ref, long clientId, Customer customer, List<BudgetLine> lines, Money total,
                           BudgetStatus status, TokenHash tokenHash, Instant tokenConsumedAt, DecisionChannel decidedBy,
                           Instant decidedAt, String voidReason, Instant createdAt, Instant expiresAt, long version) {
    }
}
