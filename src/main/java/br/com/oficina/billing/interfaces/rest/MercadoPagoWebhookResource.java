package br.com.oficina.billing.interfaces.rest;

import br.com.oficina.billing.application.ports.in.PaymentSyncUseCase;
import br.com.oficina.billing.application.ports.in.PaymentSyncUseCase.SyncResult;
import br.com.oficina.billing.application.ports.out.BillingMetricsPort;
import br.com.oficina.billing.application.ports.out.PaymentRepository.NoticeSource;
import br.com.oficina.billing.infrastructure.payment.WebhookAuthenticator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

/**
 * Notificações do Mercado Pago — a rota pública mais sensível (spec 05 §5):
 * <ol>
 *   <li>corpo limitado a 16 KB (o gateway também limita a taxa);</li>
 *   <li>assinatura {@code x-signature} verificada; falhou → 401 sem detalhe;</li>
 *   <li>do corpo só se usa o {@code data.id}: a situação vem de uma consulta à API do provedor;</li>
 *   <li>pagamento desconhecido → 200 (o provedor pararia de reenviar só com 2xx);</li>
 *   <li>resposta rápida: a transição é uma transação curta e a resposta à Saga vai pelo outbox.</li>
 * </ol>
 */
@Path("/webhooks/mercadopago")
@PermitAll
@Tag(name = "Webhook do Mercado Pago")
public class MercadoPagoWebhookResource {

    private static final Logger LOG = Logger.getLogger(MercadoPagoWebhookResource.class);
    static final int MAX_BODY = 16 * 1024;
    private static final int PAYLOAD_TOO_LARGE = 413;

    private final PaymentSyncUseCase sync;
    private final WebhookAuthenticator authenticator;
    private final BillingMetricsPort metrics;
    private final ObjectMapper mapper;

    public MercadoPagoWebhookResource(PaymentSyncUseCase sync, WebhookAuthenticator authenticator,
                                      BillingMetricsPort metrics, ObjectMapper mapper) {
        this.sync = sync;
        this.authenticator = authenticator;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Recebe a notificação de pagamento e reconsulta a situação no provedor")
    public Response receive(@QueryParam("data.id") String queryDataId, @QueryParam("type") String queryType,
                            @HeaderParam("x-signature") String signature, @HeaderParam("x-request-id") String requestId,
                            String body) {
        if (body != null && body.length() > MAX_BODY) {
            return Response.status(PAYLOAD_TOO_LARGE).build();
        }
        Optional<JsonNode> json = parse(body);
        String dataId = Optional.ofNullable(queryDataId)
            .or(() -> json.map(node -> node.path("data").path("id").asText(null)))
            .orElse(null);
        if (!authenticator.accepts(signature, requestId, dataId)) {
            metrics.webhookRejected();
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        String type = Optional.ofNullable(queryType).or(() -> json.map(node -> node.path("type").asText(null))).orElse("");
        if (!"payment".equals(type) || dataId == null || dataId.isBlank()) {
            return ok("IGNORED");
        }
        SyncResult result = sync.sync(dataId, NoticeSource.WEBHOOK);
        if (result == SyncResult.UNKNOWN) {
            LOG.infof("Notificação de pagamento desconhecido ignorada (data.id=%s)", sanitized(dataId));
        }
        return ok(result.name());
    }

    private Optional<JsonNode> parse(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readTree(body));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static Response ok(String result) {
        return Response.ok(Map.of("result", result)).build();
    }

    /** Valor vindo de fora não entra cru no log (sem quebra de linha forjada). */
    private static String sanitized(String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "?");
    }
}
