package br.com.oficina.billing.application.service.command;

import br.com.oficina.billing.application.messaging.InboundMessage;
import br.com.oficina.billing.application.ports.out.BillingMetricsPort;
import br.com.oficina.billing.application.ports.out.InboxPort;
import br.com.oficina.billing.application.ports.out.PaymentGatewayPort;
import br.com.oficina.billing.application.ports.out.PaymentGatewayPort.PaymentGatewayException;
import br.com.oficina.billing.application.ports.out.PaymentGatewayPort.RefundOutcome;
import br.com.oficina.billing.application.ports.out.PaymentRepository;
import br.com.oficina.billing.application.ports.out.TransactionRunner;
import br.com.oficina.billing.application.service.EventPublisher;
import br.com.oficina.billing.domain.event.BillingEvent;
import br.com.oficina.billing.domain.exception.BillingRuleViolation;
import br.com.oficina.billing.domain.model.Payment;
import br.com.oficina.billing.domain.valueobject.Money;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code RefundPayment} → {@code PaymentRefunded} | {@code PaymentRefundFailed} (compensação da Saga). Mesmo
 * desenho do pagamento: grava {@code REFUND_PENDING}, pede o estorno fora da transação com a chave da tentativa de
 * estorno e aplica o resultado. Um estorno recusado vira {@code REFUND_FAILED}; o retry manual da Saga reenvia o
 * comando e abre outra tentativa (outra chave).
 */
public final class RefundPaymentStrategy implements BillingCommandStrategy {

    static final String REFUND_REFUSED = "estorno recusado pelo provedor";

    private final TransactionRunner transactions;
    private final InboxPort inbox;
    private final PaymentRepository payments;
    private final PaymentGatewayPort gateway;
    private final EventPublisher events;
    private final BillingMetricsPort metrics;

    public RefundPaymentStrategy(TransactionRunner transactions, InboxPort inbox, PaymentRepository payments,
                                 PaymentGatewayPort gateway, EventPublisher events, BillingMetricsPort metrics) {
        this.transactions = transactions;
        this.inbox = inbox;
        this.payments = payments;
        this.gateway = gateway;
        this.events = events;
        this.metrics = metrics;
    }

    @Override
    public String type() {
        return "RefundPayment";
    }

    @Override
    public void handle(InboundMessage command) {
        if (inbox.wasProcessed(command.messageId())) {
            return;
        }
        transactions.inTransaction(() -> begin(command)).ifPresent(payment -> complete(command, payment));
    }

    private Optional<Refund> begin(InboundMessage command) {
        UUID paymentId = command.payload().uuid("paymentId");
        Optional<Payment> found = payments.findById(paymentId);
        if (found.isEmpty()) {
            answer(command, new BillingEvent.PaymentRefundFailed(CommandPayloads.ref(command), paymentId,
                "pagamento inexistente"));
            return Optional.empty();
        }
        Payment payment = found.get();
        boolean needsProvider;
        try {
            needsProvider = payment.beginRefund();
        } catch (BillingRuleViolation refused) {
            answer(command, new BillingEvent.PaymentRefundFailed(CommandPayloads.ref(command), paymentId,
                refused.getMessage()));
            return Optional.empty();
        }
        if (!needsProvider) {
            inbox.markProcessed(command.messageId());
            events.publish(payment.pullEvents(), command.messageId());
            return Optional.empty();
        }
        payments.save(payment);
        return Optional.of(new Refund(payment.id(), payment.providerPaymentId(), payment.refundIdempotencyKey(),
            payment.amount()));
    }

    private void complete(InboundMessage command, Refund refund) {
        String failure = null;
        RefundOutcome outcome = RefundOutcome.SUCCEEDED;
        try {
            outcome = gateway.refund(refund.providerPaymentId(), refund.amount(), refund.idempotencyKey());
        } catch (PaymentGatewayException e) {
            metrics.gatewayFailure("refund");
            failure = e.isTransient() ? RequestPaymentStrategy.UNAVAILABLE : e.getMessage();
        }
        if (outcome == RefundOutcome.FAILED) {
            failure = REFUND_REFUSED;
        }
        String reason = failure;
        boolean settled = reason != null || outcome == RefundOutcome.SUCCEEDED;
        transactions.run(() -> {
            if (!inbox.markProcessed(command.messageId())) {
                return;
            }
            if (!settled) {
                return; // pendente no provedor: o webhook (refund.updated) ou a reconciliação concluem
            }
            Payment payment = payments.findById(refund.paymentId()).orElseThrow();
            if (reason == null) {
                payment.refunded();
            } else {
                payment.refundFailed(reason);
            }
            payments.save(payment);
            events.publish(payment.pullEvents(), command.messageId());
        });
    }

    private void answer(InboundMessage command, BillingEvent event) {
        if (inbox.markProcessed(command.messageId())) {
            events.publish(event, command.messageId());
        }
    }

    private record Refund(UUID paymentId, String providerPaymentId, UUID idempotencyKey, Money amount) {
    }
}
