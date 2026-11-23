package br.com.oficina.billing.infrastructure.persistence;

import br.com.oficina.billing.domain.model.Payment;
import br.com.oficina.billing.domain.model.PaymentStatus;
import br.com.oficina.billing.domain.model.PixCharge;
import br.com.oficina.billing.domain.valueobject.Money;
import br.com.oficina.billing.domain.valueobject.SagaRef;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Tabela {@code payment}. O mapeamento fica aqui dentro de propósito: o Hibernate do Quarkus rastreia alterações
 * pelas escritas feitas nos métodos da própria entidade; uma atribuição de campo feita de fora não seria gravada.
 */
@Entity
@Table(name = "payment")
public class PaymentEntity {

    @Id
    private UUID id;

    @Column(name = "budget_id", nullable = false, updatable = false)
    private UUID budgetId;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private long workOrderId;

    @Column(name = "order_number", nullable = false, updatable = false)
    private String orderNumber;

    @Column(name = "saga_id", nullable = false, updatable = false)
    private UUID sagaId;

    @Column(nullable = false, updatable = false)
    private int attempt;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String status;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Column(name = "mp_payment_id")
    private String providerPaymentId;

    @Column(name = "qr_code")
    private String qrCode;

    @Column(name = "qr_code_base64")
    private String qrCodeBase64;

    @Column(name = "ticket_url")
    private String ticketUrl;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "failure_detail")
    private String failureDetail;

    @Column(name = "refund_attempts", nullable = false)
    private int refundAttempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PaymentEntity() {
    }

    static PaymentEntity create(Payment.Snapshot state) {
        PaymentEntity entity = new PaymentEntity();
        entity.id = state.id();
        entity.budgetId = state.budgetId();
        entity.workOrderId = state.ref().workOrderId();
        entity.orderNumber = state.ref().orderNumber();
        entity.sagaId = state.ref().sagaId();
        entity.attempt = state.attempt();
        entity.amount = state.amount().amount();
        entity.idempotencyKey = state.idempotencyKey();
        entity.createdAt = state.createdAt();
        entity.update(state);
        return entity;
    }

    void update(Payment.Snapshot state) {
        status = state.status().name();
        PixCharge charge = state.charge();
        if (charge != null) {
            providerPaymentId = charge.providerPaymentId();
            qrCode = charge.qrCode();
            qrCodeBase64 = charge.qrCodeBase64();
            ticketUrl = charge.ticketUrl();
            expiresAt = charge.expiresAt();
        }
        failureDetail = state.failureDetail();
        refundAttempts = state.refundAttempts();
        updatedAt = Instant.now();
    }

    Payment toDomain() {
        PixCharge charge = providerPaymentId == null ? null
            : new PixCharge(providerPaymentId, qrCode, qrCodeBase64, ticketUrl, expiresAt);
        return Payment.restore(new Payment.Snapshot(id, budgetId, new SagaRef(sagaId, workOrderId, orderNumber), attempt,
            new Money(amount), idempotencyKey, createdAt, PaymentStatus.valueOf(status), charge, failureDetail,
            refundAttempts, version));
    }

    long version() {
        return version;
    }
}
