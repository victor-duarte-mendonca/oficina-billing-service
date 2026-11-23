package br.com.oficina.billing.interfaces.rest;

import br.com.oficina.billing.application.dto.BudgetDetails;
import br.com.oficina.billing.application.dto.BudgetView;
import br.com.oficina.billing.application.ports.in.BudgetUseCase;
import br.com.oficina.billing.domain.model.Decision;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Orçamento visto e decidido pela oficina (o cliente aprovou ou recusou por telefone). */
@Path("/admin/budgets/{orderNumber}")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Orçamentos (oficina)")
public class BudgetAdminResource {

    static final String ORDER_NUMBER = "OS-\\d{6,}";

    private final BudgetUseCase budgets;

    public BudgetAdminResource(BudgetUseCase budgets) {
        this.budgets = budgets;
    }

    @GET
    @RolesAllowed({"ADMIN", "MECHANIC"})
    @Operation(summary = "Orçamento e todas as tentativas de pagamento da OS")
    public BudgetDetails details(@PathParam("orderNumber") @Pattern(regexp = ORDER_NUMBER) String orderNumber) {
        return budgets.detailsForStaff(orderNumber);
    }

    @POST
    @Path("/approve")
    @RolesAllowed("ADMIN")
    @Operation(summary = "Registra a aprovação dada pelo cliente ao atendimento")
    public BudgetView approve(@PathParam("orderNumber") @Pattern(regexp = ORDER_NUMBER) String orderNumber) {
        return budgets.decideAsStaff(orderNumber, Decision.APPROVE);
    }

    @POST
    @Path("/reject")
    @RolesAllowed("ADMIN")
    @Operation(summary = "Registra a recusa dada pelo cliente ao atendimento")
    public BudgetView reject(@PathParam("orderNumber") @Pattern(regexp = ORDER_NUMBER) String orderNumber) {
        return budgets.decideAsStaff(orderNumber, Decision.REJECT);
    }
}
