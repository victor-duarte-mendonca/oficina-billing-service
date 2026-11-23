package br.com.oficina.billing.domain;

import br.com.oficina.billing.domain.model.Budget;
import br.com.oficina.billing.domain.model.BudgetLine;
import br.com.oficina.billing.domain.model.Customer;
import br.com.oficina.billing.domain.model.Decision;
import br.com.oficina.billing.domain.model.DecisionChannel;
import br.com.oficina.billing.domain.valueobject.Money;
import br.com.oficina.billing.domain.valueobject.SagaRef;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Orçamentos de exemplo: 2 pastilhas a 100,00 + mão de obra 250,00 = 450,00. */
public final class Fixtures {

    public static final Instant NOW = Instant.parse("2026-11-23T14:00:00Z");
    public static final Money TOTAL = Money.of("450.00");

    private Fixtures() {
    }

    public static List<BudgetLine> lines() {
        return List.of(
            new BudgetLine(BudgetLine.ItemKind.PART, 7, "Pastilha de freio", 2, Money.of("100.00")),
            new BudgetLine(BudgetLine.ItemKind.SERVICE, 3, "Troca de pastilhas", 1, Money.of("250.00")));
    }

    public static Budget.Request request(SagaRef ref) {
        return new Budget.Request(ref, 42, new Customer("Maria", "maria@exemplo.com"), lines(), TOTAL,
            Duration.ofDays(3));
    }

    public static SagaRef ref(long workOrderId) {
        return new SagaRef(UUID.randomUUID(), workOrderId, "OS-%06d".formatted(workOrderId));
    }

    public static Budget.Issued issued() {
        Budget.Issued issued = Budget.issue(request(ref(1)), NOW);
        issued.budget().pullEvents();
        return issued;
    }

    public static Budget approved() {
        Budget budget = issued().budget();
        budget.decide(Decision.APPROVE, DecisionChannel.STAFF, NOW.plusSeconds(60));
        budget.pullEvents();
        return budget;
    }
}
