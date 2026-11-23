package br.com.oficina.billing.application.dto;

import java.util.List;

/** Visão do funcionário: o orçamento e todas as tentativas de pagamento, da mais recente à mais antiga. */
public record BudgetDetails(BudgetView budget, List<PaymentView> payments) {
}
