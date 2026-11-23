package br.com.oficina.billing.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.oficina.billing.application.ports.in.BillingMaintenanceUseCase;
import br.com.oficina.billing.infrastructure.configuration.BillingConfig;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * O relay esvazia o outbox em lotes; consumo, expiração e reconciliação rodam uma vez por disparo; só relay e
 * consumo dependem da mensageria ligada.
 */
class BillingJobsTest {

    private final Maintenance maintenance = new Maintenance();

    @Test
    void relay_shouldKeepPublishingWhileBatchesComeFull() {
        Deque<Integer> batches = new ArrayDeque<>(List.of(25, 25, 4));
        int[] calls = {0};
        OutboxPublisher publisher = new OutboxPublisher(null, null) {
            @Override
            public int publishBatch(int limit) {
                calls[0]++;
                return batches.pop();
            }
        };

        new BillingJobs(publisher, null, maintenance, config(true)).relayOutbox();

        assertThat(calls[0]).isEqualTo(3);
    }

    @Test
    void consumer_shouldPollOncePerExecution() {
        int[] polls = {0};
        CommandConsumer consumer = new CommandConsumer(null, null, null) {
            @Override
            public int pollOnce() {
                return ++polls[0];
            }
        };

        new BillingJobs(null, consumer, maintenance, config(true)).consumeCommands();

        assertThat(polls[0]).isEqualTo(1);
    }

    @Test
    void maintenance_shouldRunOncePerExecution() {
        BillingJobs jobs = new BillingJobs(null, null, maintenance, config(false));

        jobs.expireBudgets();
        jobs.reconcilePayments();
        maintenance.result = 0;
        jobs.expireBudgets();
        jobs.reconcilePayments();

        assertThat(maintenance.expirations).isEqualTo(2);
        assertThat(maintenance.reconciliations).isEqualTo(2);
    }

    @Test
    void skipPredicate_shouldFollowTheMessagingFlag() {
        assertThat(new BillingJobs.MessagingDisabled(config(false)).test(null)).isTrue();
        assertThat(new BillingJobs.MessagingDisabled(config(true)).test(null)).isFalse();
    }

    /** Só {@code messaging().enabled()} e {@code relayBatchSize()} interessam aqui. */
    private static BillingConfig config(boolean enabled) {
        BillingConfig.Messaging messaging = (BillingConfig.Messaging) Proxy.newProxyInstance(
            BillingJobsTest.class.getClassLoader(), new Class<?>[] {BillingConfig.Messaging.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "enabled" -> enabled;
                case "relayBatchSize" -> 25;
                default -> null;
            });
        return (BillingConfig) Proxy.newProxyInstance(BillingJobsTest.class.getClassLoader(),
            new Class<?>[] {BillingConfig.class},
            (proxy, method, args) -> "messaging".equals(method.getName()) ? messaging : null);
    }

    private static final class Maintenance implements BillingMaintenanceUseCase {

        int expirations;
        int reconciliations;
        int result = 2;

        @Override
        public int expireOverdueBudgets() {
            expirations++;
            return result;
        }

        @Override
        public int reconcilePayments() {
            reconciliations++;
            return result;
        }
    }
}
