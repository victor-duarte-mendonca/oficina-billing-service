package br.com.oficina.billing.infrastructure.configuration;

import br.com.oficina.billing.application.messaging.BillingMessageFactory;
import br.com.oficina.billing.application.ports.in.BudgetUseCase;
import br.com.oficina.billing.application.ports.in.HandleBillingCommandUseCase;
import br.com.oficina.billing.application.ports.out.BillingMetricsPort;
import br.com.oficina.billing.application.ports.out.BudgetNotifierPort;
import br.com.oficina.billing.application.ports.out.BudgetRepository;
import br.com.oficina.billing.application.ports.out.InboxPort;
import br.com.oficina.billing.application.ports.out.OutboxPort;
import br.com.oficina.billing.application.ports.out.PaymentGatewayPort;
import br.com.oficina.billing.application.ports.out.PaymentRepository;
import br.com.oficina.billing.application.ports.out.TransactionRunner;
import br.com.oficina.billing.application.service.BillingCommandHandler;
import br.com.oficina.billing.application.service.BudgetService;
import br.com.oficina.billing.application.service.EventPublisher;
import br.com.oficina.billing.application.service.PaymentSyncService;
import br.com.oficina.billing.application.service.command.GenerateBudgetStrategy;
import br.com.oficina.billing.application.service.command.RefundPaymentStrategy;
import br.com.oficina.billing.application.service.command.RequestPaymentStrategy;
import br.com.oficina.billing.application.service.command.VoidBudgetStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.List;

/**
 * Composição da camada de aplicação. Os casos de uso são classes Java puras (sem anotações de framework, testáveis
 * com fakes em memória); só este ponto sabe como montá-los com os adapters.
 */
@ApplicationScoped
public class ApplicationWiring {

    @Produces
    @Singleton
    EventPublisher eventPublisher(OutboxPort outbox, BillingMetricsPort metrics, Clock clock) {
        return new EventPublisher(outbox, new BillingMessageFactory(), metrics, clock);
    }

    @Produces
    @Singleton
    HandleBillingCommandUseCase commandHandler(TransactionRunner transactions, InboxPort inbox, BudgetRepository budgets,
                                               PaymentRepository payments, PaymentGatewayPort gateway,
                                               EventPublisher events, BudgetNotifierPort notifier,
                                               BillingMetricsPort metrics, Clock clock, BillingConfig config) {
        return new BillingCommandHandler(List.of(
            new GenerateBudgetStrategy(transactions, inbox, budgets, events, notifier, clock),
            new RequestPaymentStrategy(transactions, inbox, budgets, payments, gateway, events, metrics, clock,
                config.payment().pixTtl()),
            new VoidBudgetStrategy(transactions, inbox, budgets, events),
            new RefundPaymentStrategy(transactions, inbox, payments, gateway, events, metrics)));
    }

    @Produces
    @Singleton
    BudgetUseCase budgetUseCase(TransactionRunner transactions, BudgetRepository budgets, PaymentRepository payments,
                                EventPublisher events, Clock clock) {
        return new BudgetService(transactions, budgets, payments, events, clock);
    }

    /** Um bean com os dois tipos: {@code PaymentSyncUseCase} (webhook) e {@code BillingMaintenanceUseCase} (jobs). */
    @Produces
    @Singleton
    PaymentSyncService paymentSyncService(TransactionRunner transactions, BudgetRepository budgets,
                                          PaymentRepository payments, PaymentGatewayPort gateway, EventPublisher events,
                                          BillingMetricsPort metrics, Clock clock, BillingConfig config) {
        return new PaymentSyncService(transactions, budgets, payments, gateway, events, metrics, clock,
            new PaymentSyncService.Timing(config.payment().reconcileAfter(), config.payment().requestTimeout()));
    }
}
