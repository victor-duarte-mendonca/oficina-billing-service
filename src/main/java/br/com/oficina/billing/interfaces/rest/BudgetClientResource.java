package br.com.oficina.billing.interfaces.rest;

import br.com.oficina.billing.application.dto.BudgetView;
import br.com.oficina.billing.application.dto.PaymentView;
import br.com.oficina.billing.application.ports.in.BudgetUseCase;
import br.com.oficina.billing.interfaces.rest.dto.DecisionRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Área do cliente: só o orçamento das próprias OS (o {@code clientId} vem do JWT emitido pelo Lambda). OS de outro
 * cliente responde 404, como no {@code MeResource} do OS.
 */
@Path("/me/budgets/{orderNumber}")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("CLIENT")
@Tag(name = "Orçamentos (cliente)")
public class BudgetClientResource {

    private final BudgetUseCase budgets;
    private final JsonWebToken jwt;

    public BudgetClientResource(BudgetUseCase budgets, JsonWebToken jwt) {
        this.budgets = budgets;
        this.jwt = jwt;
    }

    @GET
    @Operation(summary = "Orçamento da OS do cliente")
    public BudgetView budget(@PathParam("orderNumber") @Pattern(regexp = BudgetAdminResource.ORDER_NUMBER) String orderNumber) {
        return budgets.budgetForClient(orderNumber, clientId());
    }

    @POST
    @Path("/approval")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Aprova ou recusa o orçamento")
    public BudgetView decide(@PathParam("orderNumber") @Pattern(regexp = BudgetAdminResource.ORDER_NUMBER) String orderNumber,
                             @Valid @NotNull DecisionRequest request) {
        return budgets.decideAsClient(orderNumber, clientId(), request.decision());
    }

    @GET
    @Path("/payment")
    @Operation(summary = "Pix atual: copia e cola, QR Code, validade e situação")
    public PaymentView payment(@PathParam("orderNumber") @Pattern(regexp = BudgetAdminResource.ORDER_NUMBER) String orderNumber) {
        return budgets.currentPaymentForClient(orderNumber, clientId());
    }

    /** Token de cliente sem {@code clientId} não foi emitido pelo Lambda: recusa. */
    private long clientId() {
        Object claim = jwt.getClaim("clientId");
        if (claim instanceof Number number) {
            return number.longValue();
        }
        if (claim != null) {
            try {
                return Long.parseLong(claim.toString());
            } catch (NumberFormatException e) {
                throw new ForbiddenException();
            }
        }
        throw new ForbiddenException();
    }
}
