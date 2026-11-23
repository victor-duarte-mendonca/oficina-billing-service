package br.com.oficina.billing.domain.exception;

/**
 * Orçamento inexistente <b>ou não visível</b> para quem pede (outro cliente, token errado). A mensagem é a mesma
 * nos dois casos: a rota pública não pode revelar se uma OS existe.
 */
public class BudgetNotFoundException extends RuntimeException {

    public BudgetNotFoundException(String orderNumber) {
        super("Orçamento não encontrado para a OS " + orderNumber);
    }
}
