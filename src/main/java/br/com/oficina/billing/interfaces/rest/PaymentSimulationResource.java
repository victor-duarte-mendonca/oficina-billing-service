package br.com.oficina.billing.interfaces.rest;

import br.com.oficina.billing.application.ports.in.PaymentSyncUseCase;
import br.com.oficina.billing.application.ports.out.PaymentRepository.NoticeSource;
import br.com.oficina.billing.infrastructure.configuration.BillingConfig;
import br.com.oficina.billing.infrastructure.configuration.BillingConfig.Payment.Provider;
import br.com.oficina.billing.infrastructure.payment.SimulatedPaymentGateway;
import br.com.oficina.billing.interfaces.rest.dto.SimulationRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Faz o papel do banco do cliente em dev/hml (spec 05 §4, V4): muda a situação do Pix no provedor simulado e processa
 * como se fosse o webhook. Só existe com {@code app.payment.simulation-enabled=true} e o provedor simulado; em qualquer
 * outra configuração responde 404.
 */
@Path("/admin/payments/{paymentId}/simulate")
@RolesAllowed("ADMIN")
@Tag(name = "Simulação de pagamento (dev/hml)")
public class PaymentSimulationResource {

    private final PaymentSyncUseCase sync;
    private final SimulatedPaymentGateway simulated;
    private final boolean enabled;

    public PaymentSimulationResource(PaymentSyncUseCase sync, SimulatedPaymentGateway simulated, BillingConfig config) {
        this.sync = sync;
        this.simulated = simulated;
        this.enabled = config.payment().simulationEnabled() && config.payment().provider() == Provider.SIMULATED;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Simula o pagamento, a recusa ou o vencimento do Pix")
    public Map<String, String> simulate(@PathParam("paymentId") UUID paymentId, @Valid @NotNull SimulationRequest request) {
        if (!enabled) {
            throw new NotFoundException();
        }
        String providerId = sync.providerPaymentId(paymentId).orElseThrow(NotFoundException::new);
        simulated.simulate(providerId, request.status(), request.detail());
        return Map.of("result", sync.sync(providerId, NoticeSource.API).name());
    }
}
