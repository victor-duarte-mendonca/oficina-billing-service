package br.com.oficina.billing.application.ports.out;

import br.com.oficina.billing.domain.model.Payment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Pagamentos persistidos e a trilha do que o provedor informou ({@code payment_event}). */
public interface PaymentRepository {

    Optional<Payment> findById(UUID id);

    Optional<Payment> findByProviderId(String providerPaymentId);

    Optional<Payment> findByBudgetAndAttempt(UUID budgetId, int attempt);

    /** Todos os pagamentos do orçamento, do mais recente ao mais antigo. */
    List<Payment> findByBudget(UUID budgetId);

    /** Pix ainda pendentes criados antes de {@code createdBefore}: candidatos à reconciliação. */
    List<Payment> findPendingCreatedBefore(Instant createdBefore, int limit);

    /** Pedidos que ficaram sem resposta do provedor (pod caiu no meio da chamada). */
    List<Payment> findAwaitingProviderCreatedBefore(Instant createdBefore, int limit);

    /**
     * @throws ActivePaymentConflictException se já houver outro pagamento ativo para o orçamento
     */
    void save(Payment payment);

    void recordProviderNotice(UUID paymentId, NoticeSource source, String providerStatus, Instant receivedAt);

    /** De onde veio a informação do provedor. */
    enum NoticeSource { WEBHOOK, POLL, API }

    /** O índice único parcial do banco recusou um segundo pagamento ativo para o mesmo orçamento. */
    class ActivePaymentConflictException extends RuntimeException {

        public ActivePaymentConflictException(UUID budgetId, Throwable cause) {
            super("Já existe pagamento ativo para o orçamento " + budgetId, cause);
        }
    }
}
