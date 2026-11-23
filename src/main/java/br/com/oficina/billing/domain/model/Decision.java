package br.com.oficina.billing.domain.model;

/** Decisão do cliente sobre o orçamento. */
public enum Decision {
    APPROVE(BudgetStatus.APPROVED),
    REJECT(BudgetStatus.REJECTED);

    private final BudgetStatus outcome;

    Decision(BudgetStatus outcome) {
        this.outcome = outcome;
    }

    public BudgetStatus outcome() {
        return outcome;
    }
}
