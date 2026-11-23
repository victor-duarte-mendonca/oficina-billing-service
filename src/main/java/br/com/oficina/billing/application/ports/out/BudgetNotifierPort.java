package br.com.oficina.billing.application.ports.out;

import br.com.oficina.billing.domain.model.Budget;
import br.com.oficina.billing.domain.valueobject.ApprovalToken;

/**
 * Entrega do orçamento ao cliente com o link de decisão. É o único lugar por onde o token passa em claro; falha de
 * envio não desfaz o orçamento (o cliente ainda decide pela área logada ou pelo atendimento).
 */
public interface BudgetNotifierPort {

    void budgetIssued(Budget budget, ApprovalToken token);
}
