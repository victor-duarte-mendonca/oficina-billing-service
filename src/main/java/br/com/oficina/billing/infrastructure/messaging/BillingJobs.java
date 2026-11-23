package br.com.oficina.billing.infrastructure.messaging;

import br.com.oficina.billing.application.ports.in.BillingMaintenanceUseCase;
import br.com.oficina.billing.infrastructure.configuration.BillingConfig;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import io.quarkus.scheduler.ScheduledExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

/**
 * Trabalho periódico. Relay e consumo dependem da mensageria (pulados sem filas); expiração e reconciliação rodam
 * sempre — o outbox guarda as respostas até as filas existirem. Nenhum se sobrepõe a si mesmo.
 */
@ApplicationScoped
public class BillingJobs {

    private static final Logger LOG = Logger.getLogger(BillingJobs.class);

    private final OutboxPublisher publisher;
    private final CommandConsumer consumer;
    private final BillingMaintenanceUseCase maintenance;
    private final int batch;

    public BillingJobs(OutboxPublisher publisher, CommandConsumer consumer, BillingMaintenanceUseCase maintenance,
                       BillingConfig config) {
        this.publisher = publisher;
        this.consumer = consumer;
        this.maintenance = maintenance;
        this.batch = config.messaging().relayBatchSize();
    }

    @Scheduled(identity = "outbox-relay", every = "${app.messaging.relay-interval:1s}", delayed = "${app.messaging.start-delay:5s}",
        concurrentExecution = ConcurrentExecution.SKIP, skipExecutionIf = MessagingDisabled.class)
    void relayOutbox() {
        int published;
        do {
            published = publisher.publishBatch(batch);
        } while (published == batch);
    }

    @Scheduled(identity = "billing-commands", every = "${app.messaging.poll-interval:1s}", delayed = "${app.messaging.start-delay:5s}",
        concurrentExecution = ConcurrentExecution.SKIP, skipExecutionIf = MessagingDisabled.class)
    void consumeCommands() {
        consumer.pollOnce();
    }

    @Scheduled(identity = "budget-expiry", every = "${app.budget.expiry-interval:60s}", delayed = "${app.messaging.start-delay:5s}",
        concurrentExecution = ConcurrentExecution.SKIP)
    void expireBudgets() {
        int expired = maintenance.expireOverdueBudgets();
        if (expired > 0) {
            LOG.infof("%d orçamento(s) expirado(s) sem decisão", expired);
        }
    }

    @Scheduled(identity = "payment-reconciliation", every = "${app.payment.reconcile-interval:60s}",
        delayed = "${app.messaging.start-delay:5s}", concurrentExecution = ConcurrentExecution.SKIP)
    void reconcilePayments() {
        int changed = maintenance.reconcilePayments();
        if (changed > 0) {
            LOG.infof("Reconciliação atualizou %d pagamento(s)", changed);
        }
    }

    @Singleton
    public static class MessagingDisabled implements Scheduled.SkipPredicate {

        private final BillingConfig config;

        public MessagingDisabled(BillingConfig config) {
            this.config = config;
        }

        @Override
        public boolean test(ScheduledExecution execution) {
            return !config.messaging().enabled();
        }
    }
}
