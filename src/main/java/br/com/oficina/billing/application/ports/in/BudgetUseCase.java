package br.com.oficina.billing.application.ports.in;

import br.com.oficina.billing.application.dto.BudgetDetails;
import br.com.oficina.billing.application.dto.BudgetView;
import br.com.oficina.billing.application.dto.PaymentView;
import br.com.oficina.billing.domain.model.Decision;

/**
 * Decisão e consulta do orçamento pelos três canais: funcionário (ADMIN), cliente autenticado (JWT) e link do e-mail
 * (token). Orçamento de outro cliente ou token errado respondem como inexistente.
 */
public interface BudgetUseCase {

    BudgetView decideAsStaff(String orderNumber, Decision decision);

    BudgetView decideAsClient(String orderNumber, long clientId, Decision decision);

    BudgetView decideWithToken(String orderNumber, String token, Decision decision);

    BudgetDetails detailsForStaff(String orderNumber);

    BudgetView budgetForClient(String orderNumber, long clientId);

    /** Pix mais recente do orçamento do cliente. */
    PaymentView currentPaymentForClient(String orderNumber, long clientId);
}
