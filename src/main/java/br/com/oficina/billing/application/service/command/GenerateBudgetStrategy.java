package br.com.oficina.billing.application.service.command;

import br.com.oficina.billing.application.messaging.InboundMessage;
import br.com.oficina.billing.application.ports.out.BudgetNotifierPort;
import br.com.oficina.billing.application.ports.out.BudgetRepository;
import br.com.oficina.billing.application.ports.out.InboxPort;
import br.com.oficina.billing.application.ports.out.TransactionRunner;
import br.com.oficina.billing.application.service.EventPublisher;
import br.com.oficina.billing.domain.event.BillingEvent;
import br.com.oficina.billing.domain.exception.BillingRuleViolation;
import br.com.oficina.billing.domain.model.Budget;
import br.com.oficina.billing.domain.valueobject.SagaRef;
import java.time.Clock;
import java.util.Optional;

/**
 * {@code GenerateBudget} → {@code BudgetGenerated} | {@code BudgetGenerationFailed}. O e-mail com o token sai depois
 * do commit: se a transação desfizer, o cliente não recebe um link para um orçamento que não existe.
 */
public final class GenerateBudgetStrategy implements BillingCommandStrategy {

    private final TransactionRunner transactions;
    private final InboxPort inbox;
    private final BudgetRepository budgets;
    private final EventPublisher events;
    private final BudgetNotifierPort notifier;
    private final Clock clock;

    public GenerateBudgetStrategy(TransactionRunner transactions, InboxPort inbox, BudgetRepository budgets,
                                  EventPublisher events, BudgetNotifierPort notifier, Clock clock) {
        this.transactions = transactions;
        this.inbox = inbox;
        this.budgets = budgets;
        this.events = events;
        this.notifier = notifier;
        this.clock = clock;
    }

    @Override
    public String type() {
        return "GenerateBudget";
    }

    @Override
    public void handle(InboundMessage command) {
        transactions.inTransaction(() -> issue(command))
            .ifPresent(issued -> notifier.budgetIssued(issued.budget(), issued.token()));
    }

    private Optional<Budget.Issued> issue(InboundMessage command) {
        if (!inbox.markProcessed(command.messageId())) {
            return Optional.empty();
        }
        SagaRef ref = CommandPayloads.ref(command);
        Optional<Budget> active = budgets.findActiveByWorkOrder(ref.workOrderId());
        if (active.isPresent()) {
            answerExisting(command, ref, active.get());
            return Optional.empty();
        }
        try {
            Budget.Issued issued = Budget.issue(CommandPayloads.budgetRequest(command), clock.instant());
            budgets.save(issued.budget());
            events.publish(issued.budget().pullEvents(), command.messageId());
            return Optional.of(issued);
        } catch (BillingRuleViolation refused) {
            events.publish(new BillingEvent.BudgetGenerationFailed(ref, refused.getMessage()), command.messageId());
            return Optional.empty();
        }
    }

    /** Pedido repetido da mesma Saga: mesma resposta, sem novo e-mail. De outra Saga: recusa. */
    private void answerExisting(InboundMessage command, SagaRef ref, Budget existing) {
        if (existing.belongsTo(ref.sagaId())) {
            existing.repeatGenerated();
            events.publish(existing.pullEvents(), command.messageId());
            return;
        }
        events.publish(new BillingEvent.BudgetGenerationFailed(ref,
            "A OS já tem um orçamento ativo de outro ciclo"), command.messageId());
    }
}
