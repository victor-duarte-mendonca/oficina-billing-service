package br.com.oficina.billing.application.service;

import br.com.oficina.billing.application.dto.BudgetDetails;
import br.com.oficina.billing.application.dto.BudgetView;
import br.com.oficina.billing.application.dto.PaymentView;
import br.com.oficina.billing.application.ports.in.BudgetUseCase;
import br.com.oficina.billing.application.ports.out.BudgetRepository;
import br.com.oficina.billing.application.ports.out.PaymentRepository;
import br.com.oficina.billing.application.ports.out.TransactionRunner;
import br.com.oficina.billing.domain.exception.BudgetNotFoundException;
import br.com.oficina.billing.domain.exception.PaymentNotFoundException;
import br.com.oficina.billing.domain.model.Budget;
import br.com.oficina.billing.domain.model.Decision;
import br.com.oficina.billing.domain.model.DecisionChannel;
import br.com.oficina.billing.domain.valueobject.ApprovalToken;
import java.time.Clock;
import java.util.function.Predicate;

/** Decisões e consultas do orçamento. Cada operação é uma transação; a decisão publica o evento no outbox. */
public final class BudgetService implements BudgetUseCase {

    private final TransactionRunner transactions;
    private final BudgetRepository budgets;
    private final PaymentRepository payments;
    private final EventPublisher events;
    private final Clock clock;

    public BudgetService(TransactionRunner transactions, BudgetRepository budgets, PaymentRepository payments,
                         EventPublisher events, Clock clock) {
        this.transactions = transactions;
        this.budgets = budgets;
        this.payments = payments;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public BudgetView decideAsStaff(String orderNumber, Decision decision) {
        return decide(orderNumber, budget -> true,
            budget -> budget.decide(decision, DecisionChannel.STAFF, clock.instant()));
    }

    @Override
    public BudgetView decideAsClient(String orderNumber, long clientId, Decision decision) {
        return decide(orderNumber, budget -> budget.ownedBy(clientId),
            budget -> budget.decide(decision, DecisionChannel.CLIENT_JWT, clock.instant()));
    }

    @Override
    public BudgetView decideWithToken(String orderNumber, String token, Decision decision) {
        return decide(orderNumber, budget -> true,
            budget -> budget.decideWithToken(ApprovalToken.of(token), decision, clock.instant()));
    }

    @Override
    public BudgetDetails detailsForStaff(String orderNumber) {
        return transactions.inTransaction(() -> {
            Budget budget = visible(orderNumber, any -> true);
            return new BudgetDetails(BudgetView.of(budget),
                payments.findByBudget(budget.id()).stream().map(PaymentView::of).toList());
        });
    }

    @Override
    public BudgetView budgetForClient(String orderNumber, long clientId) {
        return transactions.inTransaction(() -> BudgetView.of(visible(orderNumber, budget -> budget.ownedBy(clientId))));
    }

    @Override
    public PaymentView currentPaymentForClient(String orderNumber, long clientId) {
        return transactions.inTransaction(() -> {
            Budget budget = visible(orderNumber, candidate -> candidate.ownedBy(clientId));
            return payments.findByBudget(budget.id()).stream().findFirst().map(PaymentView::of)
                .orElseThrow(() -> new PaymentNotFoundException(orderNumber));
        });
    }

    private BudgetView decide(String orderNumber, Predicate<Budget> visibleTo, Predicate<Budget> decision) {
        return transactions.inTransaction(() -> {
            Budget budget = visible(orderNumber, visibleTo);
            if (decision.test(budget)) {
                budgets.save(budget);
                events.publish(budget.pullEvents(), null);
            }
            return BudgetView.of(budget);
        });
    }

    private Budget visible(String orderNumber, Predicate<Budget> visibleTo) {
        return budgets.findLatestByOrderNumber(orderNumber).filter(visibleTo)
            .orElseThrow(() -> new BudgetNotFoundException(orderNumber));
    }
}
