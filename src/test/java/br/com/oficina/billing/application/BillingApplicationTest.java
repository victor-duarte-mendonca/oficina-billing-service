package br.com.oficina.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.oficina.billing.application.dto.BudgetDetails;
import br.com.oficina.billing.application.messaging.BillingMessageFactory;
import br.com.oficina.billing.application.messaging.InboundMessage;
import br.com.oficina.billing.application.messaging.InvalidMessageException;
import br.com.oficina.billing.application.messaging.MessageKind;
import br.com.oficina.billing.application.messaging.OutboundMessage;
import br.com.oficina.billing.application.ports.in.PaymentSyncUseCase.SyncResult;
import br.com.oficina.billing.application.ports.out.PaymentRepository.NoticeSource;
import br.com.oficina.billing.application.service.BillingCommandHandler;
import br.com.oficina.billing.application.service.BudgetService;
import br.com.oficina.billing.application.service.EventPublisher;
import br.com.oficina.billing.application.service.PaymentSyncService;
import br.com.oficina.billing.application.service.command.GenerateBudgetStrategy;
import br.com.oficina.billing.application.service.command.RefundPaymentStrategy;
import br.com.oficina.billing.application.service.command.RequestPaymentStrategy;
import br.com.oficina.billing.application.service.command.VoidBudgetStrategy;
import br.com.oficina.billing.domain.exception.BillingRuleViolation;
import br.com.oficina.billing.domain.exception.BudgetNotFoundException;
import br.com.oficina.billing.domain.exception.PaymentNotFoundException;
import br.com.oficina.billing.domain.model.Decision;
import br.com.oficina.billing.domain.model.PaymentStatus;
import br.com.oficina.billing.domain.model.ProviderStatus;
import br.com.oficina.billing.infrastructure.payment.SimulatedPaymentGateway;
import br.com.oficina.billing.testsupport.Commands;
import br.com.oficina.billing.testsupport.InMemoryBillingStore;
import br.com.oficina.billing.testsupport.TestDoubles.MutableClock;
import br.com.oficina.billing.testsupport.TestDoubles.RecordingMetrics;
import br.com.oficina.billing.testsupport.TestDoubles.RecordingNotifier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Casos de uso do Billing com o banco em memória e o provedor simulado: cada comando da Saga, cada canal de decisão,
 * o webhook, a reconciliação e as respostas publicadas no outbox.
 */
class BillingApplicationTest {

    private static final long WORK_ORDER = 77;
    private static final String ORDER = Commands.orderNumber(WORK_ORDER);

    private InMemoryBillingStore store;
    private SimulatedPaymentGateway gateway;
    private RecordingNotifier notifier;
    private RecordingMetrics metrics;
    private MutableClock clock;
    private BillingCommandHandler handler;
    private BudgetService budgets;
    private PaymentSyncService sync;
    private UUID sagaId;

    @BeforeEach
    void setUp() {
        store = new InMemoryBillingStore();
        gateway = new SimulatedPaymentGateway();
        notifier = new RecordingNotifier();
        metrics = new RecordingMetrics();
        clock = new MutableClock(Instant.parse("2026-11-23T14:00:00Z"));
        EventPublisher events = new EventPublisher(store, new BillingMessageFactory(), metrics, clock);
        handler = new BillingCommandHandler(List.of(
            new GenerateBudgetStrategy(store, store, store.budgetRepository, events, notifier, clock),
            new RequestPaymentStrategy(store, store, store.budgetRepository, store.paymentRepository, gateway, events,
                metrics, clock, Duration.ofMinutes(30)),
            new VoidBudgetStrategy(store, store, store.budgetRepository, events),
            new RefundPaymentStrategy(store, store, store.paymentRepository, gateway, events, metrics)));
        budgets = new BudgetService(store, store.budgetRepository, store.paymentRepository, events, clock);
        sync = new PaymentSyncService(store, store.budgetRepository, store.paymentRepository, gateway, events, metrics,
            clock, new PaymentSyncService.Timing(Duration.ofMinutes(2), Duration.ofMinutes(5)));
        sagaId = UUID.randomUUID();
    }

    @Nested
    class GenerateBudget {

        @Test
        void shouldIssueTheBudgetAndEmailTheToken() {
            InboundMessage command = Commands.generateBudget(sagaId, WORK_ORDER, "450.00");

            handler.handle(command);

            OutboundMessage reply = store.lastMessage();
            assertThat(reply.type()).isEqualTo("BudgetGenerated");
            assertThat(reply.sagaId()).isEqualTo(sagaId);
            assertThat(reply.causationId()).isEqualTo(command.messageId());
            assertThat(reply.payload()).containsEntry("total", "450.00").containsKeys("budgetId", "expiresAt");
            assertThat(notifier.tokens).hasSize(1);
            assertThat(metrics.events).containsExactly("BudgetGenerated");
        }

        @Test
        void sameMessageTwice_shouldBeProcessedOnce() {
            InboundMessage command = Commands.generateBudget(sagaId, WORK_ORDER, "450.00");

            handler.handle(command);
            handler.handle(command);

            assertThat(store.outboxTypes()).containsExactly("BudgetGenerated");
            assertThat(notifier.tokens).hasSize(1);
        }

        @Test
        void resentBySameSaga_shouldRepeatTheAnswerWithoutAnotherEmail() {
            handler.handle(Commands.generateBudget(sagaId, WORK_ORDER, "450.00"));
            handler.handle(Commands.generateBudget(sagaId, WORK_ORDER, "450.00"));

            assertThat(store.outboxTypes()).containsExactly("BudgetGenerated", "BudgetGenerated");
            assertThat(store.outbox.get(0).payload().get("budgetId")).isEqualTo(store.outbox.get(1).payload().get("budgetId"));
            assertThat(notifier.tokens).hasSize(1);
        }

        @Test
        void otherSagaWhileABudgetIsActive_shouldFail() {
            handler.handle(Commands.generateBudget(sagaId, WORK_ORDER, "450.00"));
            handler.handle(Commands.generateBudget(UUID.randomUUID(), WORK_ORDER, "450.00"));

            assertThat(store.outboxTypes()).containsExactly("BudgetGenerated", "BudgetGenerationFailed");
        }

        @Test
        void totalThatDoesNotMatchTheItems_shouldFail() {
            handler.handle(Commands.generateBudget(sagaId, WORK_ORDER, "999.00"));

            assertThat(store.lastMessage().type()).isEqualTo("BudgetGenerationFailed");
            assertThat((String) store.lastMessage().payload().get("reason")).contains("difere");
            assertThat(notifier.tokens).isEmpty();
        }

        @Test
        void malformedCommand_shouldGoBackToTheQueue() {
            InboundMessage command = Commands.command("GenerateBudget", sagaId, WORK_ORDER, Map.of("clientId", 1));

            assertThatThrownBy(() -> handler.handle(command)).isInstanceOf(InvalidMessageException.class);
            assertThat(store.outbox).isEmpty();
        }
    }

    @Nested
    class Decisions {

        @Test
        void token_shouldApproveAndPublishTheDecision() {
            issueBudget();

            budgets.decideWithToken(ORDER, notifier.lastToken(), Decision.APPROVE);

            assertThat(store.lastMessage().type()).isEqualTo("BudgetApproved");
            assertThat(store.lastMessage().payload()).containsEntry("by", "CLIENT_TOKEN");
            assertThat(store.lastMessage().causationId()).isNull();
        }

        @Test
        void wrongToken_shouldLookLikeAMissingBudget() {
            issueBudget();

            assertThatThrownBy(() -> budgets.decideWithToken(ORDER, "chute", Decision.APPROVE))
                .isInstanceOf(BudgetNotFoundException.class);
            assertThatThrownBy(() -> budgets.decideWithToken("OS-999999", "chute", Decision.APPROVE))
                .isInstanceOf(BudgetNotFoundException.class);
        }

        @Test
        void client_shouldOnlyDecideTheirOwnBudget() {
            issueBudget();

            assertThatThrownBy(() -> budgets.decideAsClient(ORDER, 7, Decision.REJECT))
                .isInstanceOf(BudgetNotFoundException.class);
            assertThat(budgets.decideAsClient(ORDER, 42, Decision.REJECT).status()).isEqualTo("REJECTED");
            assertThat(store.lastMessage().payload()).containsEntry("by", "CLIENT_JWT");
        }

        @Test
        void repeatedDecision_shouldNotPublishTwice() {
            issueBudget();

            budgets.decideAsStaff(ORDER, Decision.APPROVE);
            budgets.decideAsStaff(ORDER, Decision.APPROVE);

            assertThat(store.outboxTypes()).containsExactly("BudgetGenerated", "BudgetApproved");
            assertThatThrownBy(() -> budgets.decideAsStaff(ORDER, Decision.REJECT))
                .isInstanceOf(BillingRuleViolation.class);
        }

        @Test
        void queries_shouldShowBudgetAndPayments() {
            String budgetId = approvedBudget();
            handler.handle(Commands.requestPayment(sagaId, WORK_ORDER, budgetId, "450.00", 1));

            BudgetDetails details = budgets.detailsForStaff(ORDER);

            assertThat(details.budget().status()).isEqualTo("APPROVED");
            assertThat(details.budget().items()).hasSize(2);
            assertThat(details.payments()).singleElement().satisfies(p -> {
                assertThat(p.status()).isEqualTo("PENDING");
                assertThat(p.qrCode()).isNotBlank();
            });
            assertThat(budgets.budgetForClient(ORDER, 42).total()).isEqualTo("450.00");
            assertThat(budgets.currentPaymentForClient(ORDER, 42).amount()).isEqualTo("450.00");
            assertThatThrownBy(() -> budgets.currentPaymentForClient(ORDER, 9))
                .isInstanceOf(BudgetNotFoundException.class);
        }

        @Test
        void paymentQuery_beforeAnyCharge_shouldBeNotFound() {
            issueBudget();

            assertThatThrownBy(() -> budgets.currentPaymentForClient(ORDER, 42))
                .isInstanceOf(PaymentNotFoundException.class);
        }
    }

    @Nested
    class RequestPayment {

        @Test
        void shouldCreateThePixAndAnswerPaymentRequested() {
            String budgetId = approvedBudget();

            handler.handle(Commands.requestPayment(sagaId, WORK_ORDER, budgetId, "450.00", 1));

            assertThat(store.lastMessage().type()).isEqualTo("PaymentRequested");
            assertThat(store.lastMessage().payload()).containsKeys("paymentId", "expiresAt");
        }

        @Test
        void resentWithTheSameAttempt_shouldNotChargeTwice() {
            String budgetId = approvedBudget();

            handler.handle(Commands.requestPayment(sagaId, WORK_ORDER, budgetId, "450.00", 1));
            handler.handle(Commands.requestPayment(sagaId, WORK_ORDER, budgetId, "450.00", 1));

            assertThat(store.outboxTypes()).endsWith("PaymentRequested", "PaymentRequested");
            assertThat(store.paymentRepository.findByBudget(UUID.fromString(budgetId))).hasSize(1);
        }

        @Test
        void differentAmount_shouldBeRefused() {
            String budgetId = approvedBudget();

            handler.handle(Commands.requestPayment(sagaId, WORK_ORDER, budgetId, "10.00", 1));

            assertThat(store.lastMessage().type()).isEqualTo("PaymentRequestFailed");
            assertThat((String) store.lastMessage().payload().get("reason")).contains("difere");
        }

        @Test
        void unknownBudget_shouldBeRefused() {
            handler.handle(Commands.requestPayment(sagaId, WORK_ORDER, UUID.randomUUID().toString(), "450.00", 1));

            assertThat(store.lastMessage().type()).isEqualTo("PaymentRequestFailed");
        }

        @Test
        void providerUnavailable_shouldAnswerFailure() {
            String budgetId = approvedBudget();
            gateway.failNextCreations(1);

            handler.handle(Commands.requestPayment(sagaId, WORK_ORDER, budgetId, "450.00", 1));

            assertThat(store.lastMessage().type()).isEqualTo("PaymentRequestFailed");
            assertThat(store.lastMessage().payload()).containsEntry("reason", "provedor de pagamento indisponível");
            assertThat(metrics.gatewayFailures).containsExactly("create");
        }

        @Test
        void secondAttemptWhileTheFirstIsPending_shouldBeRefused() {
            String budgetId = approvedBudget();
            handler.handle(Commands.requestPayment(sagaId, WORK_ORDER, budgetId, "450.00", 1));

            handler.handle(Commands.requestPayment(sagaId, WORK_ORDER, budgetId, "450.00", 2));

            assertThat(store.lastMessage().type()).isEqualTo("PaymentRequestFailed");
            assertThat((String) store.lastMessage().payload().get("reason")).contains("em andamento");
        }

        @Test
        void newAttemptAfterRejection_shouldCreateAnotherPix() {
            String budgetId = approvedBudget();
            String first = requestPix(budgetId, 1);
            gateway.simulate(providerId(first), ProviderStatus.REJECTED, "cc_rejected");
            sync.sync(providerId(first), NoticeSource.WEBHOOK);

            handler.handle(Commands.requestPayment(sagaId, WORK_ORDER, budgetId, "450.00", 2));

            assertThat(store.outboxTypes()).endsWith("PaymentRejected", "PaymentRequested");
            assertThat(store.paymentRepository.findByBudget(UUID.fromString(budgetId))).hasSize(2);
        }
    }

    @Nested
    class ProviderNotifications {

        @Test
        void approval_shouldBeConfirmedWithTheProviderAndPublishedOnce() {
            String paymentId = requestPix(approvedBudget(), 1);
            gateway.simulate(providerId(paymentId), ProviderStatus.APPROVED, "accredited");

            assertThat(sync.sync(providerId(paymentId), NoticeSource.WEBHOOK)).isEqualTo(SyncResult.UPDATED);
            assertThat(sync.sync(providerId(paymentId), NoticeSource.WEBHOOK)).isEqualTo(SyncResult.UNCHANGED);

            assertThat(store.outboxTypes()).containsOnlyOnce("PaymentApproved");
            assertThat(store.lastMessage().payload()).containsEntry("amount", "450.00");
            assertThat(store.notices).containsExactly("WEBHOOK:APPROVED", "WEBHOOK:APPROVED");
        }

        @Test
        void unknownPayment_shouldBeIgnored() {
            assertThat(sync.sync("123456", NoticeSource.WEBHOOK)).isEqualTo(SyncResult.UNKNOWN);
            assertThat(sync.providerPaymentId(UUID.randomUUID())).isEmpty();
        }

        @Test
        void reconciliation_shouldCatchALostWebhook() {
            String paymentId = requestPix(approvedBudget(), 1);
            gateway.simulate(providerId(paymentId), ProviderStatus.APPROVED, "accredited");

            assertThat(sync.reconcilePayments()).isZero();
            clock.advance(Duration.ofMinutes(3));
            assertThat(sync.reconcilePayments()).isEqualTo(1);

            assertThat(store.lastMessage().type()).isEqualTo("PaymentApproved");
            assertThat(store.notices).containsExactly("POLL:APPROVED");
        }

        @Test
        void reconciliation_shouldExpireAPixPastItsDeadline() {
            String paymentId = requestPix(approvedBudget(), 1);
            clock.advance(Duration.ofMinutes(31));

            assertThat(sync.reconcilePayments()).isEqualTo(1);

            assertThat(store.lastMessage().type()).isEqualTo("PaymentExpired");
            assertThat(store.paymentRepository.findById(UUID.fromString(paymentId)).orElseThrow().status())
                .isEqualTo(PaymentStatus.EXPIRED);
        }

        @Test
        void reconciliation_withTheProviderDown_shouldStillHonourTheDeadline() {
            requestPix(approvedBudget(), 1);
            gateway.setUnavailable(true);
            clock.advance(Duration.ofMinutes(10));
            assertThat(sync.reconcilePayments()).isZero();

            clock.advance(Duration.ofMinutes(25));
            assertThat(sync.reconcilePayments()).isEqualTo(1);
            assertThat(store.lastMessage().type()).isEqualTo("PaymentExpired");
            assertThat(metrics.gatewayFailures).contains("find");
        }

        @Test
        void requestThatNeverGotAnAnswer_shouldBeClosedAsFailure() {
            String budgetId = approvedBudget();
            UUID budget = UUID.fromString(budgetId);
            var payment = br.com.oficina.billing.domain.model.Payment.request(
                store.budgetRepository.findById(budget).orElseThrow(), 1,
                br.com.oficina.billing.domain.valueobject.Money.of("450.00"), clock.instant());
            store.paymentRepository.save(payment);
            clock.advance(Duration.ofMinutes(6));

            assertThat(sync.reconcilePayments()).isEqualTo(1);

            assertThat(store.lastMessage().type()).isEqualTo("PaymentRequestFailed");
        }

        @Test
        void overdueBudgets_shouldExpire() {
            issueBudget();
            assertThat(sync.expireOverdueBudgets()).isZero();

            clock.advance(Duration.ofDays(3));

            assertThat(sync.expireOverdueBudgets()).isEqualTo(1);
            assertThat(store.lastMessage().type()).isEqualTo("BudgetExpired");
        }
    }

    @Nested
    class Compensations {

        @Test
        void refund_shouldReturnTheMoney() {
            String paymentId = paidPix();

            handler.handle(Commands.refundPayment(sagaId, WORK_ORDER, paymentId));

            assertThat(store.lastMessage().type()).isEqualTo("PaymentRefunded");
            assertThat(gateway.isRefunded(providerId(paymentId))).isTrue();
        }

        @Test
        void refusedRefund_shouldBeReportedAndRetriable() {
            String paymentId = paidPix();
            gateway.refuseRefunds("saldo insuficiente");

            handler.handle(Commands.refundPayment(sagaId, WORK_ORDER, paymentId));
            assertThat(store.lastMessage().type()).isEqualTo("PaymentRefundFailed");
            assertThat(store.lastMessage().payload()).containsEntry("detail", "saldo insuficiente");

            gateway.refuseRefunds(null);
            handler.handle(Commands.refundPayment(sagaId, WORK_ORDER, paymentId));
            assertThat(store.lastMessage().type()).isEqualTo("PaymentRefunded");
        }

        @Test
        void refundAlreadyDone_shouldJustConfirm() {
            String paymentId = paidPix();
            handler.handle(Commands.refundPayment(sagaId, WORK_ORDER, paymentId));

            handler.handle(Commands.refundPayment(sagaId, WORK_ORDER, paymentId));

            assertThat(store.outboxTypes()).endsWith("PaymentRefunded", "PaymentRefunded");
        }

        @Test
        void refundWithoutCapturedMoney_shouldFail() {
            String pending = requestPix(approvedBudget(), 1);

            handler.handle(Commands.refundPayment(sagaId, WORK_ORDER, pending));
            assertThat(store.lastMessage().type()).isEqualTo("PaymentRefundFailed");

            handler.handle(Commands.refundPayment(sagaId, WORK_ORDER, UUID.randomUUID().toString()));
            assertThat(store.lastMessage().payload()).containsEntry("detail", "pagamento inexistente");
        }

        @Test
        void refundWithTheProviderDown_shouldFailAsUnavailable() {
            String paymentId = paidPix();
            gateway.setUnavailable(true);

            handler.handle(Commands.refundPayment(sagaId, WORK_ORDER, paymentId));

            assertThat(store.lastMessage().payload()).containsEntry("detail", "provedor de pagamento indisponível");
            assertThat(metrics.gatewayFailures).containsExactly("refund");
        }

        @Test
        void void_shouldAlwaysConfirm() {
            String budgetId = approvedBudget();

            handler.handle(Commands.voidBudget(sagaId, WORK_ORDER, budgetId));
            handler.handle(Commands.voidBudget(sagaId, WORK_ORDER, UUID.randomUUID().toString()));

            assertThat(store.outboxTypes()).endsWith("BudgetVoided", "BudgetVoided");
            assertThat(budgets.budgetForClient(ORDER, 42).status()).isEqualTo("VOIDED");
        }
    }

    @Test
    void handler_shouldRefuseWhatIsNotABillingCommand() {
        InboundMessage unknown = Commands.command("EnqueueExecution", sagaId, WORK_ORDER, Map.of());
        InboundMessage event = InboundMessage.of(UUID.randomUUID(), "GenerateBudget", MessageKind.EVENT, sagaId,
            WORK_ORDER, ORDER, Map.of());

        assertThatThrownBy(() -> handler.handle(unknown)).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> handler.handle(event)).isInstanceOf(InvalidMessageException.class);
    }

    // ---- cenário

    private void issueBudget() {
        handler.handle(Commands.generateBudget(sagaId, WORK_ORDER, "450.00"));
    }

    private String approvedBudget() {
        issueBudget();
        return budgets.decideAsStaff(ORDER, Decision.APPROVE).budgetId();
    }

    private String requestPix(String budgetId, int attempt) {
        handler.handle(Commands.requestPayment(sagaId, WORK_ORDER, budgetId, "450.00", attempt));
        return (String) store.lastMessage().payload().get("paymentId");
    }

    private String paidPix() {
        String paymentId = requestPix(approvedBudget(), 1);
        gateway.simulate(providerId(paymentId), ProviderStatus.APPROVED, "accredited");
        sync.sync(providerId(paymentId), NoticeSource.WEBHOOK);
        return paymentId;
    }

    private String providerId(String paymentId) {
        return sync.providerPaymentId(UUID.fromString(paymentId)).orElseThrow();
    }
}
