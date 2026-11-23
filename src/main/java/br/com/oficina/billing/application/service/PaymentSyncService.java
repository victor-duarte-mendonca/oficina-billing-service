package br.com.oficina.billing.application.service;

import br.com.oficina.billing.application.ports.in.BillingMaintenanceUseCase;
import br.com.oficina.billing.application.ports.in.PaymentSyncUseCase;
import br.com.oficina.billing.application.ports.out.BillingMetricsPort;
import br.com.oficina.billing.application.ports.out.BudgetRepository;
import br.com.oficina.billing.application.ports.out.PaymentGatewayPort;
import br.com.oficina.billing.application.ports.out.PaymentGatewayPort.PaymentGatewayException;
import br.com.oficina.billing.application.ports.out.PaymentGatewayPort.ProviderPayment;
import br.com.oficina.billing.application.ports.out.PaymentRepository;
import br.com.oficina.billing.application.ports.out.PaymentRepository.NoticeSource;
import br.com.oficina.billing.application.ports.out.TransactionRunner;
import br.com.oficina.billing.domain.model.Budget;
import br.com.oficina.billing.domain.model.Payment;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sincronização com o provedor (webhook e reconciliação) e expiração de orçamentos. A consulta ao provedor é feita
 * fora da transação; o efeito (transição, trilha em {@code payment_event} e evento no outbox) numa transação curta.
 */
public final class PaymentSyncService implements PaymentSyncUseCase, BillingMaintenanceUseCase {

    private static final int BATCH = 50;
    private static final String NO_PROVIDER_ANSWER = "sem resposta do provedor de pagamento";

    private final TransactionRunner transactions;
    private final BudgetRepository budgets;
    private final PaymentRepository payments;
    private final PaymentGatewayPort gateway;
    private final EventPublisher events;
    private final BillingMetricsPort metrics;
    private final Clock clock;
    private final Timing timing;

    public PaymentSyncService(TransactionRunner transactions, BudgetRepository budgets, PaymentRepository payments,
                              PaymentGatewayPort gateway, EventPublisher events, BillingMetricsPort metrics, Clock clock,
                              Timing timing) {
        this.transactions = transactions;
        this.budgets = budgets;
        this.payments = payments;
        this.gateway = gateway;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
        this.timing = timing;
    }

    @Override
    public SyncResult sync(String providerPaymentId, NoticeSource source) {
        boolean known = transactions.inTransaction(() -> payments.findByProviderId(providerPaymentId).isPresent());
        if (!known) {
            return SyncResult.UNKNOWN;
        }
        ProviderPayment remote = consult(providerPaymentId);
        return transactions.inTransaction(() -> {
            Payment payment = payments.findByProviderId(providerPaymentId).orElseThrow();
            payments.recordProviderNotice(payment.id(), source, remote.status().name(), clock.instant());
            return apply(payment, payment.applyProviderStatus(remote.status(), remote.detail()));
        });
    }

    @Override
    public Optional<String> providerPaymentId(UUID paymentId) {
        return transactions.inTransaction(() -> payments.findById(paymentId).map(Payment::providerPaymentId));
    }

    @Override
    public int expireOverdueBudgets() {
        return transactions.inTransaction(() -> {
            Instant now = clock.instant();
            List<Budget> overdue = budgets.findOverdue(now, BATCH);
            overdue.forEach(budget -> {
                budget.expireIfOverdue(now);
                budgets.save(budget);
                events.publish(budget.pullEvents(), null);
            });
            return overdue.size();
        });
    }

    @Override
    public int reconcilePayments() {
        Instant now = clock.instant();
        List<Payment> pending = transactions.inTransaction(
            () -> payments.findPendingCreatedBefore(now.minus(timing.reconcileAfter()), BATCH));
        int changed = (int) pending.stream().filter(payment -> reconcile(payment.providerPaymentId(), now)).count();
        return changed + closeUnanswered(now);
    }

    private boolean reconcile(String providerPaymentId, Instant now) {
        Optional<ProviderPayment> remote;
        try {
            remote = Optional.of(consult(providerPaymentId));
        } catch (PaymentGatewayException e) {
            remote = Optional.empty();
        }
        Optional<ProviderPayment> answer = remote;
        return transactions.inTransaction(() -> {
            Payment payment = payments.findByProviderId(providerPaymentId).orElseThrow();
            boolean updated = answer.map(found -> {
                payments.recordProviderNotice(payment.id(), NoticeSource.POLL, found.status().name(), now);
                return payment.applyProviderStatus(found.status(), found.detail());
            }).orElse(false);
            return apply(payment, updated || payment.expireIfOverdue(now)) == SyncResult.UPDATED;
        });
    }

    /** Pedido gravado e nunca concluído (pod caiu e o comando foi parar na DLQ): a Saga recebe a falha. */
    private int closeUnanswered(Instant now) {
        return transactions.inTransaction(() -> {
            List<Payment> stuck = payments.findAwaitingProviderCreatedBefore(now.minus(timing.requestTimeout()), BATCH);
            stuck.forEach(payment -> {
                payment.creationFailed(NO_PROVIDER_ANSWER);
                apply(payment, true);
            });
            return stuck.size();
        });
    }

    private ProviderPayment consult(String providerPaymentId) {
        try {
            return gateway.find(providerPaymentId);
        } catch (PaymentGatewayException e) {
            metrics.gatewayFailure("find");
            throw e;
        }
    }

    private SyncResult apply(Payment payment, boolean changed) {
        if (!changed) {
            return SyncResult.UNCHANGED;
        }
        payments.save(payment);
        events.publish(payment.pullEvents(), null);
        return SyncResult.UPDATED;
    }

    /**
     * @param reconcileAfter idade mínima de um Pix pendente para ser consultado (dá tempo ao webhook)
     * @param requestTimeout tempo depois do qual um pedido sem resposta do provedor é encerrado
     */
    public record Timing(Duration reconcileAfter, Duration requestTimeout) {
    }
}
