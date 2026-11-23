package br.com.oficina.billing.domain.event;

import br.com.oficina.billing.domain.model.DecisionChannel;
import br.com.oficina.billing.domain.valueobject.Money;
import br.com.oficina.billing.domain.valueobject.SagaRef;
import java.time.Instant;
import java.util.UUID;

/**
 * Fatos do Billing que a Saga do OS precisa saber. Cada um vira uma mensagem de
 * {@code contracts/billing-events.schema.json} com o mesmo nome; o tipo do contrato é o nome simples do record.
 */
public sealed interface BillingEvent {

    SagaRef ref();

    default String type() {
        return getClass().getSimpleName();
    }

    record BudgetGenerated(SagaRef ref, UUID budgetId, Money total, Instant expiresAt) implements BillingEvent {
    }

    record BudgetGenerationFailed(SagaRef ref, String reason) implements BillingEvent {
    }

    record BudgetApproved(SagaRef ref, UUID budgetId, DecisionChannel by) implements BillingEvent {
    }

    record BudgetRejected(SagaRef ref, UUID budgetId, DecisionChannel by) implements BillingEvent {
    }

    record BudgetExpired(SagaRef ref, UUID budgetId) implements BillingEvent {
    }

    record BudgetVoided(SagaRef ref, UUID budgetId) implements BillingEvent {
    }

    record PaymentRequested(SagaRef ref, UUID paymentId, Instant expiresAt) implements BillingEvent {
    }

    record PaymentRequestFailed(SagaRef ref, String reason) implements BillingEvent {
    }

    record PaymentApproved(SagaRef ref, UUID paymentId, Money amount) implements BillingEvent {
    }

    record PaymentRejected(SagaRef ref, UUID paymentId, String detail) implements BillingEvent {
    }

    record PaymentExpired(SagaRef ref, UUID paymentId) implements BillingEvent {
    }

    record PaymentRefunded(SagaRef ref, UUID paymentId) implements BillingEvent {
    }

    record PaymentRefundFailed(SagaRef ref, UUID paymentId, String detail) implements BillingEvent {
    }
}
