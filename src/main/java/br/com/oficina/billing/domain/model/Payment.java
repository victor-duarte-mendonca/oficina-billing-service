package br.com.oficina.billing.domain.model;

import br.com.oficina.billing.domain.event.BillingEvent;
import br.com.oficina.billing.domain.exception.BillingRuleViolation;
import br.com.oficina.billing.domain.valueobject.Money;
import br.com.oficina.billing.domain.valueobject.SagaRef;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pagamento Pix de um orçamento (aggregate root). Cada tentativa da Saga é um pagamento novo; a chave de
 * idempotência é derivada de (orçamento, tentativa), então o mesmo pedido reenviado nunca gera duas cobranças.
 * Transições repetidas (webhook duplicado, reconciliação depois do webhook) não geram evento de novo.
 */
public final class Payment {

    private final UUID id;
    private final UUID budgetId;
    private final SagaRef ref;
    private final int attempt;
    private final Money amount;
    private final UUID idempotencyKey;
    private final Instant createdAt;
    private final long version;

    private PaymentStatus status;
    private PixCharge charge;
    private String failureDetail;
    private int refundAttempts;
    private final List<BillingEvent> events = new ArrayList<>();

    private Payment(Snapshot state) {
        this.id = Objects.requireNonNull(state.id());
        this.budgetId = Objects.requireNonNull(state.budgetId());
        this.ref = Objects.requireNonNull(state.ref());
        this.attempt = state.attempt();
        this.amount = Objects.requireNonNull(state.amount());
        this.idempotencyKey = Objects.requireNonNull(state.idempotencyKey());
        this.createdAt = Objects.requireNonNull(state.createdAt());
        this.version = state.version();
        this.status = Objects.requireNonNull(state.status());
        this.charge = state.charge();
        this.failureDetail = state.failureDetail();
        this.refundAttempts = state.refundAttempts();
    }

    /**
     * Registra a intenção de cobrar, antes de falar com o provedor.
     *
     * @throws BillingRuleViolation orçamento não aprovado, valor diferente do total, tentativa inválida
     */
    public static Payment request(Budget budget, int attempt, Money requested, Instant now) {
        if (budget.status() != BudgetStatus.APPROVED) {
            throw new BillingRuleViolation("Pagamento só de orçamento aprovado (situação atual: " + budget.status() + ")");
        }
        if (requested.compareTo(budget.total()) != 0) {
            throw new BillingRuleViolation("Valor do pagamento (" + requested + ") difere do total do orçamento ("
                + budget.total() + ")");
        }
        if (attempt < 1) {
            throw new BillingRuleViolation("Tentativa de pagamento inválida: " + attempt);
        }
        UUID key = UUID.nameUUIDFromBytes(("payment:" + budget.id() + ":" + attempt).getBytes(StandardCharsets.UTF_8));
        return new Payment(new Snapshot(UUID.randomUUID(), budget.id(), budget.ref(), attempt, budget.total(), key,
            now, PaymentStatus.REQUESTED, null, null, 0, 0));
    }

    public static Payment restore(Snapshot state) {
        return new Payment(state);
    }

    /** O provedor criou a cobrança. Repetido (reentrega do comando) não gera outro evento. */
    public void attachCharge(PixCharge pix) {
        if (status != PaymentStatus.REQUESTED) {
            return;
        }
        charge = pix;
        status = PaymentStatus.PENDING;
        events.add(new BillingEvent.PaymentRequested(ref, id, pix.expiresAt()));
    }

    /** O provedor recusou ou está indisponível: a Saga decide se tenta de novo (outra tentativa, outra chave). */
    public void creationFailed(String reason) {
        if (status != PaymentStatus.REQUESTED) {
            return;
        }
        status = PaymentStatus.FAILED;
        failureDetail = reason;
        events.add(new BillingEvent.PaymentRequestFailed(ref, reason));
    }

    /**
     * Situação confirmada no provedor (webhook ou reconciliação — nunca o corpo do webhook).
     *
     * @return {@code true} se mudou
     */
    public boolean applyProviderStatus(ProviderStatus reported, String detail) {
        if (reported == ProviderStatus.APPROVED && (status == PaymentStatus.PENDING || isClosedWithoutMoney())) {
            status = PaymentStatus.APPROVED;
            events.add(new BillingEvent.PaymentApproved(ref, id, amount));
            return true;
        }
        if (status != PaymentStatus.PENDING) {
            return false;
        }
        return switch (reported) {
            case REJECTED -> close(PaymentStatus.REJECTED, detail == null ? "rejected" : detail);
            case EXPIRED -> close(PaymentStatus.EXPIRED, detail);
            case PENDING, APPROVED -> false;
        };
    }

    /** Prazo do Pix vencido e o provedor ainda não confirmou: a Saga não pode esperar para sempre. */
    public boolean expireIfOverdue(Instant now) {
        if (status != PaymentStatus.PENDING || charge == null || now.isBefore(charge.expiresAt())) {
            return false;
        }
        return close(PaymentStatus.EXPIRED, "prazo do Pix encerrado sem confirmação");
    }

    /**
     * Compensação: inicia (ou retoma) o estorno.
     *
     * @return {@code true} se há estorno a pedir ao provedor; {@code false} se já foi estornado
     */
    public boolean beginRefund() {
        switch (status) {
            case APPROVED, REFUND_FAILED -> {
                status = PaymentStatus.REFUND_PENDING;
                refundAttempts++;
                return true;
            }
            case REFUND_PENDING -> {
                return true;
            }
            case REFUNDED -> {
                events.add(new BillingEvent.PaymentRefunded(ref, id));
                return false;
            }
            default -> throw new BillingRuleViolation("Pagamento " + status + " não tem valor capturado para estornar");
        }
    }

    /** Chave do estorno: a mesma enquanto a tentativa de estorno for a mesma (retry seguro no provedor). */
    public UUID refundIdempotencyKey() {
        return UUID.nameUUIDFromBytes(("refund:" + id + ":" + refundAttempts).getBytes(StandardCharsets.UTF_8));
    }

    public void refunded() {
        if (status == PaymentStatus.REFUND_PENDING) {
            status = PaymentStatus.REFUNDED;
            failureDetail = null;
            events.add(new BillingEvent.PaymentRefunded(ref, id));
        }
    }

    public void refundFailed(String detail) {
        if (status == PaymentStatus.REFUND_PENDING) {
            status = PaymentStatus.REFUND_FAILED;
            failureDetail = detail;
            events.add(new BillingEvent.PaymentRefundFailed(ref, id, detail));
        }
    }

    /** Reenvio da resposta atual para um pedido repetido com outro {@code messageId} (mesma tentativa). */
    public void repeatRequestOutcome() {
        switch (status) {
            case PENDING, APPROVED, REJECTED, EXPIRED, REFUND_PENDING, REFUNDED, REFUND_FAILED ->
                events.add(new BillingEvent.PaymentRequested(ref, id, charge.expiresAt()));
            case FAILED -> events.add(new BillingEvent.PaymentRequestFailed(ref, failureDetail));
            case REQUESTED -> { /* a chamada ao provedor ainda vai responder */ }
        }
    }

    public boolean isAwaitingProvider() {
        return status == PaymentStatus.REQUESTED;
    }

    public List<BillingEvent> pullEvents() {
        List<BillingEvent> pulled = List.copyOf(events);
        events.clear();
        return pulled;
    }

    public Snapshot snapshot() {
        return new Snapshot(id, budgetId, ref, attempt, amount, idempotencyKey, createdAt, status, charge, failureDetail,
            refundAttempts, version);
    }

    public UUID id() { return id; }
    public UUID budgetId() { return budgetId; }
    public SagaRef ref() { return ref; }
    public int attempt() { return attempt; }
    public Money amount() { return amount; }
    public UUID idempotencyKey() { return idempotencyKey; }
    public PaymentStatus status() { return status; }
    public PixCharge charge() { return charge; }
    public String failureDetail() { return failureDetail; }

    public String providerPaymentId() {
        return charge == null ? null : charge.providerPaymentId();
    }

    private boolean isClosedWithoutMoney() {
        return status == PaymentStatus.REJECTED || status == PaymentStatus.EXPIRED;
    }

    private boolean close(PaymentStatus closed, String detail) {
        status = closed;
        failureDetail = detail;
        events.add(closed == PaymentStatus.REJECTED
            ? new BillingEvent.PaymentRejected(ref, id, detail)
            : new BillingEvent.PaymentExpired(ref, id));
        return true;
    }

    /** Estado completo para persistência (Memento). */
    public record Snapshot(UUID id, UUID budgetId, SagaRef ref, int attempt, Money amount, UUID idempotencyKey,
                           Instant createdAt, PaymentStatus status, PixCharge charge, String failureDetail,
                           int refundAttempts, long version) {
    }
}
