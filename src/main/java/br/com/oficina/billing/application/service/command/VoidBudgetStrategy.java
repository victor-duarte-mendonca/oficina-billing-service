package br.com.oficina.billing.application.service.command;

import br.com.oficina.billing.application.messaging.InboundMessage;
import br.com.oficina.billing.application.ports.out.BudgetRepository;
import br.com.oficina.billing.application.ports.out.InboxPort;
import br.com.oficina.billing.application.ports.out.TransactionRunner;
import br.com.oficina.billing.application.service.EventPublisher;
import br.com.oficina.billing.domain.event.BillingEvent;
import br.com.oficina.billing.domain.model.Budget;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code VoidBudget} → {@code BudgetVoided}. Compensação nunca falha por regra: anular de novo, ou anular um
 * orçamento que não chegou a existir, confirma do mesmo jeito (a Saga só precisa saber que não há o que cobrar).
 */
public final class VoidBudgetStrategy implements BillingCommandStrategy {

    private final TransactionRunner transactions;
    private final InboxPort inbox;
    private final BudgetRepository budgets;
    private final EventPublisher events;

    public VoidBudgetStrategy(TransactionRunner transactions, InboxPort inbox, BudgetRepository budgets,
                              EventPublisher events) {
        this.transactions = transactions;
        this.inbox = inbox;
        this.budgets = budgets;
        this.events = events;
    }

    @Override
    public String type() {
        return "VoidBudget";
    }

    @Override
    public void handle(InboundMessage command) {
        transactions.run(() -> {
            if (!inbox.markProcessed(command.messageId())) {
                return;
            }
            UUID budgetId = command.payload().uuid("budgetId");
            String reason = command.payload().text("reason");
            Optional<Budget> budget = budgets.findById(budgetId);
            if (budget.isEmpty()) {
                events.publish(new BillingEvent.BudgetVoided(CommandPayloads.ref(command), budgetId), command.messageId());
                return;
            }
            budget.get().voidBudget(reason);
            budgets.save(budget.get());
            events.publish(budget.get().pullEvents(), command.messageId());
        });
    }
}
