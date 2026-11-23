package br.com.oficina.billing.domain;

import static br.com.oficina.billing.domain.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.oficina.billing.domain.event.BillingEvent;
import br.com.oficina.billing.domain.exception.BillingRuleViolation;
import br.com.oficina.billing.domain.model.Budget;
import br.com.oficina.billing.domain.model.Payment;
import br.com.oficina.billing.domain.model.PaymentStatus;
import br.com.oficina.billing.domain.model.PixCharge;
import br.com.oficina.billing.domain.model.ProviderStatus;
import br.com.oficina.billing.domain.valueobject.Money;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private static final Instant PIX_DEADLINE = NOW.plusSeconds(1800);

    @Test
    void request_shouldRequireAnApprovedBudgetAndItsExactTotal() {
        Budget sent = Fixtures.issued().budget();
        Budget approved = Fixtures.approved();

        assertThatThrownBy(() -> Payment.request(sent, 1, Fixtures.TOTAL, NOW))
            .isInstanceOf(BillingRuleViolation.class).hasMessageContaining("aprovado");
        assertThatThrownBy(() -> Payment.request(approved, 1, Money.of("449.99"), NOW))
            .isInstanceOf(BillingRuleViolation.class).hasMessageContaining("difere");
        assertThatThrownBy(() -> Payment.request(approved, 0, Fixtures.TOTAL, NOW))
            .isInstanceOf(BillingRuleViolation.class).hasMessageContaining("Tentativa");

        Payment payment = Payment.request(approved, 1, Money.of("450"), NOW);
        assertThat(payment.status()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(payment.isAwaitingProvider()).isTrue();
        assertThat(payment.pullEvents()).isEmpty();
    }

    @Test
    void idempotencyKey_shouldDependOnlyOnBudgetAndAttempt() {
        Budget approved = Fixtures.approved();

        Payment first = Payment.request(approved, 1, Fixtures.TOTAL, NOW);
        Payment again = Payment.request(approved, 1, Fixtures.TOTAL, NOW.plusSeconds(5));
        Payment retry = Payment.request(approved, 2, Fixtures.TOTAL, NOW);

        assertThat(again.idempotencyKey()).isEqualTo(first.idempotencyKey());
        assertThat(retry.idempotencyKey()).isNotEqualTo(first.idempotencyKey());
    }

    @Test
    void charge_shouldBeAttachedOnce() {
        Payment payment = pending();

        payment.attachCharge(charge("mp-2"));

        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.providerPaymentId()).isEqualTo("mp-1");
        assertThat(payment.pullEvents()).isEmpty();
    }

    @Test
    void creationFailure_shouldTellTheSaga() {
        Payment payment = Payment.request(Fixtures.approved(), 1, Fixtures.TOTAL, NOW);

        payment.creationFailed("provedor indisponível");
        payment.attachCharge(charge("tarde"));

        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.pullEvents()).containsExactly(
            new BillingEvent.PaymentRequestFailed(payment.ref(), "provedor indisponível"));
        payment.repeatRequestOutcome();
        assertThat(payment.pullEvents()).singleElement().isInstanceOf(BillingEvent.PaymentRequestFailed.class);
    }

    @Test
    void providerStatus_shouldMoveAPendingPaymentOnce() {
        Payment payment = pending();

        assertThat(payment.applyProviderStatus(ProviderStatus.PENDING, "waiting")).isFalse();
        assertThat(payment.applyProviderStatus(ProviderStatus.APPROVED, "accredited")).isTrue();
        assertThat(payment.applyProviderStatus(ProviderStatus.APPROVED, "accredited")).isFalse();
        assertThat(payment.applyProviderStatus(ProviderStatus.REJECTED, "x")).isFalse();

        assertThat(payment.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.pullEvents()).containsExactly(
            new BillingEvent.PaymentApproved(payment.ref(), payment.id(), Fixtures.TOTAL));
    }

    @Test
    void rejectionAndExpiry_shouldCloseThePayment() {
        Payment rejected = pending();
        Payment expired = pending();

        assertThat(rejected.applyProviderStatus(ProviderStatus.REJECTED, null)).isTrue();
        assertThat(expired.applyProviderStatus(ProviderStatus.EXPIRED, "cancelled")).isTrue();

        assertThat(rejected.pullEvents()).containsExactly(
            new BillingEvent.PaymentRejected(rejected.ref(), rejected.id(), "rejected"));
        assertThat(expired.pullEvents()).containsExactly(new BillingEvent.PaymentExpired(expired.ref(), expired.id()));
        assertThat(PaymentStatus.REJECTED.isActive()).isFalse();
    }

    @Test
    void lateApproval_shouldStillBeReportedSoTheSagaRefundsIt() {
        Payment payment = pending();
        payment.applyProviderStatus(ProviderStatus.EXPIRED, "cancelled");
        payment.pullEvents();

        assertThat(payment.applyProviderStatus(ProviderStatus.APPROVED, "accredited")).isTrue();

        assertThat(payment.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.pullEvents()).singleElement().isInstanceOf(BillingEvent.PaymentApproved.class);
    }

    @Test
    void localDeadline_shouldExpireAPixTheProviderNeverConfirmed() {
        Payment payment = pending();

        assertThat(payment.expireIfOverdue(PIX_DEADLINE.minusSeconds(1))).isFalse();
        assertThat(payment.expireIfOverdue(PIX_DEADLINE)).isTrue();
        assertThat(payment.expireIfOverdue(PIX_DEADLINE)).isFalse();
        assertThat(payment.failureDetail()).contains("prazo");
    }

    @Test
    void refund_shouldGoThroughPendingAndBeRetriableAfterFailure() {
        Payment payment = approvedPayment();

        assertThat(payment.beginRefund()).isTrue();
        var firstKey = payment.refundIdempotencyKey();
        assertThat(payment.beginRefund()).isTrue();
        assertThat(payment.refundIdempotencyKey()).isEqualTo(firstKey);
        payment.refundFailed("saldo insuficiente");
        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_FAILED);

        assertThat(payment.beginRefund()).isTrue();
        assertThat(payment.refundIdempotencyKey()).isNotEqualTo(firstKey);
        payment.refunded();
        payment.refunded();

        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.pullEvents()).containsExactly(
            new BillingEvent.PaymentRefundFailed(payment.ref(), payment.id(), "saldo insuficiente"),
            new BillingEvent.PaymentRefunded(payment.ref(), payment.id()));
        assertThat(payment.beginRefund()).isFalse();
        assertThat(payment.pullEvents()).singleElement().isInstanceOf(BillingEvent.PaymentRefunded.class);
    }

    @Test
    void refund_ofMoneyNeverCaptured_shouldBeRefused() {
        Payment payment = pending();

        assertThatThrownBy(payment::beginRefund).isInstanceOf(BillingRuleViolation.class);
        payment.refundFailed("ignorado");
        payment.refunded();
        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void repeatedRequest_shouldAnswerWithTheCurrentCharge() {
        Payment payment = approvedPayment();

        payment.repeatRequestOutcome();

        assertThat(payment.pullEvents()).containsExactly(
            new BillingEvent.PaymentRequested(payment.ref(), payment.id(), PIX_DEADLINE));
        Payment inFlight = Payment.request(Fixtures.approved(), 1, Fixtures.TOTAL, NOW);
        inFlight.repeatRequestOutcome();
        assertThat(inFlight.pullEvents()).isEmpty();
    }

    @Test
    void snapshot_shouldRestoreTheSamePayment() {
        Payment payment = approvedPayment();

        Payment restored = Payment.restore(payment.snapshot());

        assertThat(restored.snapshot()).isEqualTo(payment.snapshot());
        assertThat(restored.budgetId()).isEqualTo(payment.budgetId());
        assertThat(restored.attempt()).isEqualTo(1);
        assertThat(restored.amount()).isEqualTo(Fixtures.TOTAL);
        assertThat(restored.charge().qrCode()).isEqualTo("copia-e-cola");
    }

    private static Payment pending() {
        Payment payment = Payment.request(Fixtures.approved(), 1, Fixtures.TOTAL, NOW);
        payment.attachCharge(charge("mp-1"));
        assertThat(payment.pullEvents()).containsExactly(
            new BillingEvent.PaymentRequested(payment.ref(), payment.id(), PIX_DEADLINE));
        return payment;
    }

    private static Payment approvedPayment() {
        Payment payment = pending();
        payment.applyProviderStatus(ProviderStatus.APPROVED, "accredited");
        payment.pullEvents();
        return payment;
    }

    private static PixCharge charge(String id) {
        return new PixCharge(id, "copia-e-cola", "aW1n", "https://pix/" + id, PIX_DEADLINE);
    }
}
