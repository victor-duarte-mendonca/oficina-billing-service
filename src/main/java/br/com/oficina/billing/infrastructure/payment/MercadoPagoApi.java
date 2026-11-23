package br.com.oficina.billing.infrastructure.payment;

import br.com.oficina.billing.application.ports.out.PaymentGatewayPort.PaymentGatewayException;
import br.com.oficina.billing.infrastructure.configuration.BillingConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;

/**
 * Cliente HTTP da API do Mercado Pago (Payments API, {@code /v1/payments}). Só esta classe vê o access token, e ele
 * nunca vai para log nem para mensagem de erro.
 *
 * <p>Resiliência (spec 05 §6): timeouts de conexão e leitura do próprio cliente; <i>retry</i> com backoff apenas em
 * falha transitória (rede, 5xx, 429) — seguro porque toda escrita leva {@code X-Idempotency-Key}; <i>circuit
 * breaker</i> para não insistir num provedor fora do ar.</p>
 */
@ApplicationScoped
public class MercadoPagoApi {

    private static final int TOO_MANY_REQUESTS = 429;

    private final HttpClient http;
    private final URI baseUrl;
    private final String accessToken;
    private final Duration readTimeout;
    private final ObjectMapper mapper;

    @Inject
    public MercadoPagoApi(BillingConfig config, ObjectMapper mapper) {
        this(config.payment().mercadopago().baseUrl(), config.payment().mercadopago().accessToken().orElse(""),
            config.payment().mercadopago().connectTimeout(), config.payment().mercadopago().readTimeout(), mapper);
    }

    public MercadoPagoApi(URI baseUrl, String accessToken, Duration connectTimeout, Duration readTimeout,
                          ObjectMapper mapper) {
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
        this.readTimeout = readTimeout;
        this.mapper = mapper;
    }

    @Retry(maxRetries = 2, delay = 300, jitter = 150, retryOn = ProviderUnavailableException.class)
    @CircuitBreaker(requestVolumeThreshold = 6, failureRatio = 0.5, delay = 30_000,
        failOn = ProviderUnavailableException.class)
    public JsonNode post(String path, UUID idempotencyKey, Object body) {
        return send(request(path)
            .header("X-Idempotency-Key", idempotencyKey.toString())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json(body)))
            .build());
    }

    @Retry(maxRetries = 2, delay = 300, jitter = 150, retryOn = ProviderUnavailableException.class)
    @CircuitBreaker(requestVolumeThreshold = 6, failureRatio = 0.5, delay = 30_000,
        failOn = ProviderUnavailableException.class)
    public JsonNode get(String path) {
        return send(request(path).GET().build());
    }

    private HttpRequest.Builder request(String path) {
        if (accessToken.isBlank()) {
            throw new PaymentGatewayException("Access token do Mercado Pago não configurado", false);
        }
        return HttpRequest.newBuilder(baseUrl.resolve(path))
            .timeout(readTimeout)
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json");
    }

    private JsonNode send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ProviderUnavailableException("falha de rede com o Mercado Pago: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("chamada ao Mercado Pago interrompida", e);
        }
        int status = response.statusCode();
        if (status >= 500 || status == TOO_MANY_REQUESTS) {
            throw new ProviderUnavailableException("Mercado Pago respondeu " + status, null);
        }
        JsonNode body = parse(response.body());
        if (status >= 400) {
            throw new PaymentGatewayException("Mercado Pago recusou (" + status + "): "
                + body.path("message").asText("sem detalhe"), false);
        }
        return body;
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ProviderUnavailableException("resposta do Mercado Pago não é JSON", e);
        }
    }

    private String json(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar o pedido ao Mercado Pago", e);
        }
    }

    /** Falha transitória: vale repetir (com a mesma chave de idempotência). */
    public static class ProviderUnavailableException extends PaymentGatewayException {

        public ProviderUnavailableException(String message, Throwable cause) {
            super(message, true, cause);
        }
    }
}
