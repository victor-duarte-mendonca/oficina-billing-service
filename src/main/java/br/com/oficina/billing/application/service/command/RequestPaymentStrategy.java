package br.com.oficina.billing.application.service.command;

import br.com.oficina.billing.application.messaging.InboundMessage;
import br.com.oficina.billing.application.ports.out.BillingMetricsPort;
import br.com.oficina.billing.application.ports.out.BudgetRepository;
import br.com.oficina.billing.application.ports.out.InboxPort;
import br.com.oficina.billing.application.ports.out.PaymentGatewayPort;
import br.com.oficina.billing.application.ports.out.PaymentGatewayPort.PaymentGatewayException;
import br.com.oficina.billing.application.ports.out.PaymentGatewayPort.PixRequest;
import br.com.oficina.billing.application.ports.out.PaymentRepository;
import br.com.oficina.billing.application.ports.out.PaymentRepository.ActivePaymentConflictException;
import br.com.oficina.billing.application.ports.out.TransactionRunner;
import br.com.oficina.billing.application.service.EventPublisher;
import br.com.oficina.billing.domain.event.BillingEvent;
import br.com.oficina.billing.domain.exception.BillingRuleViolation;
import br.com.oficina.billing.domain.model.Budget;
import br.com.oficina.billing.domain.model.Payment;
import br.com.oficina.billing.domain.model.PixCharge;
import br.com.oficina.billing.domain.valueobject.Money;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code RequestPayment} → {@code PaymentRequested} | {@code PaymentRequestFailed}, em três passos:
 * <ol>
 *   <li>transação 1: valida e grava o pagamento {@code REQUESTED} com a chave de idempotência;</li>
 *   <li>fora de transação: cria o Pix no provedor com essa chave (retry e circuit breaker ficam no adapter);</li>
 *   <li>transação 2: aplica o resultado, publica a resposta e marca o comando como processado.</li>
 * </ol>
 * Se o pod cair entre 1 e 3, a reentrega encontra o pagamento {@code REQUESTED} e repete a chamada com a mesma
 * chave: o provedor devolve a mesma cobrança em vez de criar outra.
 */
public final class RequestPaymentStrategy implements BillingCommandStrategy {

    static final String UNAVAILABLE = "provedor de pagamento indisponível";

    private final TransactionRunner transactions;
    private final InboxPort inbox;
    private final BudgetRepository budgets;
    private final PaymentRepository payments;
    private final PaymentGatewayPort gateway;
    private final EventPublisher events;
    private final BillingMetricsPort metrics;
    private final Clock clock;
    private final Duration pixTtl;

    public RequestPaymentStrategy(TransactionRunner transactions, InboxPort inbox, BudgetRepository budgets,
                                  PaymentRepository payments, PaymentGatewayPort gateway, EventPublisher events,
                                  BillingMetricsPort metrics, Clock clock, Duration pixTtl) {
        this.transactions = transactions;
        this.inbox = inbox;
        this.budgets = budgets;
        this.payments = payments;
        this.gateway = gateway;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
        this.pixTtl = pixTtl;
    }

    @Override
    public String type() {
        return "RequestPayment";
    }

    @Override
    public void handle(InboundMessage command) {
        if (inbox.wasProcessed(command.messageId())) {
            return;
        }
        Optional<PixRequest> call;
        try {
            call = transactions.inTransaction(() -> prepare(command));
        } catch (ActivePaymentConflictException conflict) {
            refuse(command, "o orçamento já tem um pagamento em andamento");
            return;
        }
        call.ifPresent(request -> complete(command, request));
    }

    /** @return o pedido ao provedor, ou vazio quando a resposta já foi dada nesta transação */
    private Optional<PixRequest> prepare(InboundMessage command) {
        UUID budgetId = command.payload().uuid("budgetId");
        Money amount = command.payload().money("amount");
        int attempt = command.payload().integer("attempt");
        Optional<Budget> budget = budgets.findById(budgetId);
        if (budget.isEmpty()) {
            answer(command, new BillingEvent.PaymentRequestFailed(CommandPayloads.ref(command), "orçamento inexistente"));
            return Optional.empty();
        }
        Optional<Payment> existing = payments.findByBudgetAndAttempt(budgetId, attempt);
        if (existing.isPresent() && !existing.get().isAwaitingProvider()) {
            existing.get().repeatRequestOutcome();
            inbox.markProcessed(command.messageId());
            events.publish(existing.get().pullEvents(), command.messageId());
            return Optional.empty();
        }
        Payment payment;
        try {
            payment = existing.orElseGet(() -> Payment.request(budget.get(), attempt, amount, clock.instant()));
        } catch (BillingRuleViolation refused) {
            answer(command, new BillingEvent.PaymentRequestFailed(CommandPayloads.ref(command), refused.getMessage()));
            return Optional.empty();
        }
        payments.save(payment);
        return Optional.of(new PixRequest(payment.idempotencyKey(), payment.id(), payment.amount(),
            "Oficina — " + payment.ref().orderNumber(), budget.get().customer().optionalEmail(),
            clock.instant().plus(pixTtl)));
    }

    private void complete(InboundMessage command, PixRequest request) {
        Optional<PixCharge> charge;
        String failure = null;
        try {
            charge = Optional.of(gateway.createPix(request));
        } catch (PaymentGatewayException e) {
            metrics.gatewayFailure("create");
            charge = Optional.empty();
            failure = e.isTransient() ? UNAVAILABLE : "pagamento recusado pelo provedor: " + e.getMessage();
        }
        Optional<PixCharge> result = charge;
        String reason = failure;
        transactions.run(() -> {
            if (!inbox.markProcessed(command.messageId())) {
                return;
            }
            Payment payment = payments.findById(request.paymentId()).orElseThrow();
            result.ifPresentOrElse(payment::attachCharge, () -> payment.creationFailed(reason));
            payments.save(payment);
            events.publish(payment.pullEvents(), command.messageId());
        });
    }

    private void refuse(InboundMessage command, String reason) {
        transactions.run(() -> answer(command,
            new BillingEvent.PaymentRequestFailed(CommandPayloads.ref(command), reason)));
    }

    private void answer(InboundMessage command, BillingEvent event) {
        if (inbox.markProcessed(command.messageId())) {
            events.publish(event, command.messageId());
        }
    }
}
