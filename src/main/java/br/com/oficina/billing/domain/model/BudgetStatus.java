package br.com.oficina.billing.domain.model;

/**
 * Ciclo do orçamento: {@code SENT → APPROVED | REJECTED | EXPIRED}, e qualquer um deles {@code → VOIDED} quando a
 * Saga desfaz o ciclo. Não há {@code DRAFT} persistido: o orçamento nasce do comando já completo e é enviado na
 * mesma transação (o rascunho é a OS, que fica no OS Service).
 */
public enum BudgetStatus {
    SENT, APPROVED, REJECTED, EXPIRED, VOIDED;

    /** Ativo = ainda conta para a regra de "um orçamento vivo por OS" (índice único parcial no banco). */
    public boolean isActive() {
        return this == SENT || this == APPROVED;
    }
}
