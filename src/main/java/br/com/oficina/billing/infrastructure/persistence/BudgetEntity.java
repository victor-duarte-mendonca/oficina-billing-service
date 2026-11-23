package br.com.oficina.billing.infrastructure.persistence;

import br.com.oficina.billing.domain.model.Budget;
import br.com.oficina.billing.domain.model.BudgetLine;
import br.com.oficina.billing.domain.model.BudgetStatus;
import br.com.oficina.billing.domain.model.Customer;
import br.com.oficina.billing.domain.model.DecisionChannel;
import br.com.oficina.billing.domain.valueobject.ApprovalToken.TokenHash;
import br.com.oficina.billing.domain.valueobject.Money;
import br.com.oficina.billing.domain.valueobject.SagaRef;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tabela {@code budget} (e {@code budget_line}). Só a infraestrutura conhece esta classe; o domínio usa
 * {@code Budget.Snapshot}. O mapeamento fica aqui dentro porque o Hibernate do Quarkus rastreia alterações pelas
 * escritas feitas nos métodos da própria entidade.
 */
@Entity
@Table(name = "budget")
public class BudgetEntity {

    @Id
    private UUID id;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private long workOrderId;

    @Column(name = "order_number", nullable = false, updatable = false)
    private String orderNumber;

    @Column(name = "saga_id", nullable = false, updatable = false)
    private UUID sagaId;

    @Column(name = "client_id", nullable = false, updatable = false)
    private long clientId;

    @Column(name = "customer_name", nullable = false, updatable = false)
    private String customerName;

    @Column(name = "customer_email", updatable = false)
    private String customerEmail;

    @Column(nullable = false, updatable = false)
    private BigDecimal total;

    @Column(nullable = false)
    private String status;

    @Column(name = "approval_token_hash", nullable = false, updatable = false)
    private String approvalTokenHash;

    @Column(name = "token_consumed_at")
    private Instant tokenConsumedAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "void_reason")
    private String voidReason;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "budget_line", joinColumns = @JoinColumn(name = "budget_id"))
    @OrderColumn(name = "position")
    private List<LineEmbeddable> lines = new ArrayList<>();

    protected BudgetEntity() {
    }

    static BudgetEntity create(Budget.Snapshot state) {
        BudgetEntity entity = new BudgetEntity();
        entity.id = state.id();
        entity.workOrderId = state.ref().workOrderId();
        entity.orderNumber = state.ref().orderNumber();
        entity.sagaId = state.ref().sagaId();
        entity.clientId = state.clientId();
        entity.customerName = state.customer().name();
        entity.customerEmail = state.customer().email();
        entity.total = state.total().amount();
        entity.approvalTokenHash = state.tokenHash().hex();
        entity.expiresAt = state.expiresAt();
        entity.createdAt = state.createdAt();
        entity.lines = state.lines().stream().map(LineEmbeddable::of).collect(Collectors.toCollection(ArrayList::new));
        entity.update(state);
        return entity;
    }

    void update(Budget.Snapshot state) {
        status = state.status().name();
        tokenConsumedAt = state.tokenConsumedAt();
        decidedBy = state.decidedBy() == null ? null : state.decidedBy().name();
        decidedAt = state.decidedAt();
        voidReason = state.voidReason();
        updatedAt = Instant.now();
    }

    Budget toDomain() {
        return Budget.restore(new Budget.Snapshot(id, new SagaRef(sagaId, workOrderId, orderNumber), clientId,
            new Customer(customerName, customerEmail), lines.stream().map(LineEmbeddable::toDomain).toList(),
            new Money(total), BudgetStatus.valueOf(status), new TokenHash(approvalTokenHash), tokenConsumedAt,
            decidedBy == null ? null : DecisionChannel.valueOf(decidedBy), decidedAt, voidReason, createdAt, expiresAt,
            version));
    }

    long version() {
        return version;
    }

    /** Linha do orçamento ({@code budget_line}); a posição preserva a ordem do comando. */
    @Embeddable
    public static class LineEmbeddable {

        @Column(nullable = false)
        private String kind;

        @Column(name = "ref_id", nullable = false)
        private long refId;

        @Column(nullable = false)
        private String name;

        @Column(nullable = false)
        private int quantity;

        @Column(name = "unit_price", nullable = false)
        private BigDecimal unitPrice;

        protected LineEmbeddable() {
        }

        static LineEmbeddable of(BudgetLine line) {
            LineEmbeddable embeddable = new LineEmbeddable();
            embeddable.kind = line.kind().name();
            embeddable.refId = line.refId();
            embeddable.name = line.name();
            embeddable.quantity = line.quantity();
            embeddable.unitPrice = line.unitPrice().amount();
            return embeddable;
        }

        BudgetLine toDomain() {
            return new BudgetLine(BudgetLine.ItemKind.valueOf(kind), refId, name, quantity, new Money(unitPrice));
        }
    }
}
