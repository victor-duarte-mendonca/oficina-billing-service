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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

/**
 * Eventos da Stripe — a rota pública mais sensível (spec 05 §5):
 * <ol>
 *   <li>corpo limitado a 64 KB (o gateway também limita a taxa);</li>
 *   <li>{@code Stripe-Signature} verificada sobre o corpo cru, com tolerância de tempo; falhou → 401 sem detalhe;</li>
 *   <li>do evento só se usa o id do PaymentIntent: a situação vem de uma consulta à API da Stripe;</li>
 *   <li>pagamento desconhecido ou evento de outro tipo → 200 (a Stripe só para de reenviar com 2xx);</li>
 *   <li>reentrega do mesmo evento é inofensiva: a transição é idempotente;</li>
 *   <li>resposta rápida: a transição é uma transação curta e a resposta à Saga vai pelo outbox.</li>
 * </ol>
 */
@Path("/webhooks/stripe")
@PermitAll
@Tag(name = "Webhook da Stripe")
public class StripeWebhookResource {

    private static final Logger LOG = Logger.getLogger(StripeWebhookResource.class);
    static final int MAX_BODY = 64 * 1024;
    private static final int PAYLOAD_TOO_LARGE = 413;

    private final PaymentSyncUseCase sync;
    private final WebhookAuthenticator authenticator;
    private final BillingMetricsPort metrics;
    private final ObjectMapper mapper;

    public StripeWebhookResource(PaymentSyncUseCase sync, WebhookAuthenticator authenticator,
                                 BillingMetricsPort metrics, ObjectMapper mapper) {
        this.sync = sync;
        this.authenticator = authenticator;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Recebe o evento da Stripe e reconsulta a situação do pagamento na API")
    public Response receive(@HeaderParam("Stripe-Signature") String signature, String body) {
        if (body != null && body.length() > MAX_BODY) {
            return Response.status(PAYLOAD_TOO_LARGE).build();
        }
        if (!authenticator.accepts(signature, body)) {
            metrics.webhookRejected();
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        JsonNode event = parse(body).orElse(null);
        if (event == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        String type = event.path("type").asText("");
        JsonNode object = event.path("data").path("object");
        if ("charge.dispute.created".equals(type)) {
            LOG.warnf("Contestação de Pix recebida (payment_intent=%s)", sanitized(object.path("payment_intent").asText("?")));
            return ok("IGNORED");
        }
        String paymentIntentId = paymentIntentId(type, object);
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            return ok("IGNORED");
        }
        SyncResult result = sync.sync(paymentIntentId, NoticeSource.WEBHOOK);
        if (result == SyncResult.UNKNOWN) {
            LOG.infof("Evento de pagamento desconhecido ignorado (payment_intent=%s)", sanitized(paymentIntentId));
        }
        return ok(result.name());
    }

    /** Eventos do PaymentIntent trazem o próprio objeto; os de cobrança e estorno apontam para ele. */
    static String paymentIntentId(String type, JsonNode object) {
        return switch (type) {
            case "payment_intent.succeeded", "payment_intent.payment_failed", "payment_intent.canceled" ->
                object.path("id").asText(null);
            case "charge.refunded", "refund.updated" -> object.path("payment_intent").asText(null);
            default -> null;
        };
    }

    private Optional<JsonNode> parse(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(body);
            return node.isObject() ? Optional.of(node) : Optional.empty();
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
