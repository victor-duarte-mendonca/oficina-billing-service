package br.com.oficina.billing.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.oficina.billing.application.ports.in.BillingMaintenanceUseCase;
import br.com.oficina.billing.application.ports.out.BudgetRepository;
import br.com.oficina.billing.domain.model.BudgetLine;
import br.com.oficina.billing.domain.model.BudgetStatus;
import br.com.oficina.billing.domain.model.Customer;
import br.com.oficina.billing.domain.valueobject.Money;
import br.com.oficina.billing.domain.valueobject.SagaRef;
import br.com.oficina.billing.infrastructure.messaging.MessageTransport;
import br.com.oficina.billing.infrastructure.messaging.OutboxPublisher;
import java.time.Duration;
import br.com.oficina.billing.application.ports.out.ConcurrentUpdateException;
import br.com.oficina.billing.application.ports.out.PaymentRepository;
import br.com.oficina.billing.application.ports.out.PaymentRepository.ActivePaymentConflictException;
import br.com.oficina.billing.domain.model.Budget;
import br.com.oficina.billing.domain.model.Decision;
import br.com.oficina.billing.domain.model.DecisionChannel;
import br.com.oficina.billing.domain.model.Payment;
import br.com.oficina.billing.testsupport.BillingScenario;
import br.com.oficina.billing.testsupport.BillingScenario.Order;
import br.com.oficina.billing.testsupport.LocalStackResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Invariantes garantidas pelo PostgreSQL (spec 02 §5): um pagamento ativo por orçamento mesmo com duas transações
 * concorrentes, trava otimista do orçamento e mapeamento completo do agregado (ida e volta).
 */
@QuarkusTest
@QuarkusTestResource(LocalStackResource.class)
class PersistenceIntegrationTest {

    @Inject BillingScenario scenario;
    @Inject BudgetRepository budgets;
    @Inject PaymentRepository payments;
    @Inject EntityManager em;
    @Inject BillingMaintenanceUseCase maintenance;
    @Inject OutboxRepositoryAdapter outbox;
    @Inject OutboxPublisher realPublisher;

    @Test
    void twoConcurrentPayments_forTheSameBudget_onlyOneSurvives() throws Exception {
        Order order = scenario.issued();
        UUID budgetId = UUID.fromString(scenario.approvedBudget(order));
        Budget budget = read(() -> budgets.findById(budgetId).orElseThrow());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = List.of(
                pool.submit(insert(budget, 1, start)), pool.submit(insert(budget, 2, start)));
            start.countDown();

            List<String> outcomes = List.of(results.get(0).get(), results.get(1).get());
            assertThat(outcomes).containsExactlyInAnyOrder("ok", "conflict");
        } finally {
            pool.shutdownNow();
        }
        assertThat(read(() -> payments.findByBudget(budgetId))).hasSize(1);
    }

    @Test
    void staleBudget_shouldNotOverwriteANewerDecision() {
        Order order = scenario.issued();
        Budget stale = read(() -> budgets.findLatestByOrderNumber(order.orderNumber()).orElseThrow());
        scenario.approvedBudget(order);

        stale.decide(Decision.REJECT, DecisionChannel.STAFF, Instant.now());

        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> budgets.save(stale)))
            .isInstanceOf(ConcurrentUpdateException.class);
    }

    @Test
    void aggregates_shouldSurviveTheRoundTrip() {
        Order order = scenario.issued();
        String paymentId = scenario.paidPix(order);

        Budget budget = read(() -> budgets.findLatestByOrderNumber(order.orderNumber()).orElseThrow());
        Payment payment = read(() -> payments.findById(UUID.fromString(paymentId)).orElseThrow());

        assertThat(budget.lines()).extracting(line -> line.name()).containsExactly("Pastilha de freio", "Troca de pastilhas");
        assertThat(budget.total().toWire()).isEqualTo("450.00");
        assertThat(budget.snapshot().version()).isEqualTo(1);
        assertThat(payment.charge().qrCode()).isNotBlank();
        assertThat(read(() -> payments.findByProviderId(payment.providerPaymentId()))).isPresent();
        assertThat(read(() -> budgets.findActiveByWorkOrder(order.workOrderId()))).isPresent();
        String tokenColumn = read(() -> (String) em.createNativeQuery(
            "SELECT approval_token_hash FROM budget WHERE id = ?1").setParameter(1, budget.id()).getSingleResult());
        assertThat(tokenColumn).hasSize(64).isNotEqualTo(scenario.emailedToken(order));
    }

    @Test
    void overdueBudget_shouldBeExpiredByTheJobQuery() {
        Order order = scenario.newOrder();
        Budget.Issued issued = Budget.issue(new Budget.Request(
            new SagaRef(order.sagaId(), order.workOrderId(), order.orderNumber()), 42,
            new Customer("Maria", null), List.of(new BudgetLine(BudgetLine.ItemKind.SERVICE, 1, "Revisão", 1,
                Money.of("100.00"))), Money.of("100.00"), Duration.ofSeconds(1)),
            Instant.now().minus(Duration.ofHours(1)));
        QuarkusTransaction.requiringNew().run(() -> budgets.save(issued.budget()));

        assertThat(maintenance.expireOverdueBudgets()).isPositive();

        assertThat(read(() -> budgets.findById(issued.budget().id()).orElseThrow().status()))
            .isEqualTo(BudgetStatus.EXPIRED);
    }

    @Test
    void relay_shouldStopAtTheFirstFailureAndRecordIt() {
        scenario.issued();
        MessageTransport offline = new MessageTransport() {
            @Override
            public void sendToOrderService(String groupId, UUID deduplicationId, String body) {
                throw new IllegalStateException("fila fora do ar");
            }

            @Override
            public List<ReceivedMessage> receiveCommands() {
                return List.of();
            }

            @Override
            public void acknowledge(ReceivedMessage message) {
            }
        };
        OutboxPublisher publisher = new OutboxPublisher(outbox, offline);

        int published = QuarkusTransaction.requiringNew().call(() -> publisher.publishBatch(10));

        assertThat(published).isZero();
        Number failed = read(() -> (Number) em.createNativeQuery(
            "SELECT count(*) FROM outbox WHERE published_at IS NULL AND last_error = 'fila fora do ar'").getSingleResult());
        assertThat(failed.intValue()).isEqualTo(1);
        realPublisher.publishBatch(500);
    }

    private Callable<String> insert(Budget budget, int attempt, CountDownLatch start) {
        return () -> {
            start.await();
            try {
                QuarkusTransaction.requiringNew().run(() ->
                    payments.save(Payment.request(budget, attempt, budget.total(), Instant.now())));
                return "ok";
            } catch (ActivePaymentConflictException e) {
                return "conflict";
            }
        };
    }

    private static <T> T read(Callable<T> query) {
        return QuarkusTransaction.requiringNew().call(query);
    }
}
