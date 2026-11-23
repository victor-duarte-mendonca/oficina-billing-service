package br.com.oficina.billing.interfaces.rest;

import br.com.oficina.billing.application.dto.BudgetView;
import br.com.oficina.billing.application.ports.in.BudgetUseCase;
import br.com.oficina.billing.domain.model.Decision;
import br.com.oficina.billing.interfaces.rest.dto.TokenDecisionRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Decisão pelo link do e-mail, sem login. A autorização é o token de uso único; token errado responde 404 igual a
 * OS inexistente (não revela quais OS existem). O API Gateway limita a taxa desta rota.
 */
@Path("/public/budgets/{orderNumber}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@PermitAll
@Tag(name = "Orçamentos (link do e-mail)")
public class BudgetPublicResource {

    private final BudgetUseCase budgets;

    public BudgetPublicResource(BudgetUseCase budgets) {
        this.budgets = budgets;
    }

    @POST
    @Path("/approve")
    @Operation(summary = "Aprova o orçamento com o código recebido por e-mail")
    public BudgetView approve(@PathParam("orderNumber") @Pattern(regexp = BudgetAdminResource.ORDER_NUMBER) String orderNumber,
                              @Valid @NotNull TokenDecisionRequest request) {
        return budgets.decideWithToken(orderNumber, request.token(), Decision.APPROVE);
    }

    @POST
    @Path("/reject")
    @Operation(summary = "Recusa o orçamento com o código recebido por e-mail")
    public BudgetView reject(@PathParam("orderNumber") @Pattern(regexp = BudgetAdminResource.ORDER_NUMBER) String orderNumber,
                             @Valid @NotNull TokenDecisionRequest request) {
        return budgets.decideWithToken(orderNumber, request.token(), Decision.REJECT);
    }
}
