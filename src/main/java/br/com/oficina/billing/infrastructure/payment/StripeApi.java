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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;

/**
 * Cliente HTTP da API da Stripe (ADR 0023). Só esta classe vê a chave de API, e ela nunca vai para log nem para
 * mensagem de erro (qualquer chave que a Stripe devolva no texto do erro é mascarada).
 *
 * <p>A API recebe {@code application/x-www-form-urlencoded}. Resiliência (spec 05 §6): timeouts do próprio cliente;
 * <i>retry</i> com backoff só em falha transitória (rede, 5xx, 429, 409) — seguro porque toda escrita leva
 * {@code Idempotency-Key}; <i>circuit breaker</i> para não insistir num provedor fora do ar.</p>
 */
@ApplicationScoped
public class StripeApi {

    private static final int TOO_MANY_REQUESTS = 429;
    private static final int CONFLICT = 409;
    private static final String SECRET_KEY_PATTERN = "(sk|rk|pk)_(test|live)_[A-Za-z0-9]+";

    private final HttpClient http;
    private final URI baseUrl;
    private final String apiKey;
    private final Optional<String> apiVersion;
    private final Duration readTimeout;
    private final ObjectMapper mapper;

    @Inject
    public StripeApi(BillingConfig config, ObjectMapper mapper) {
        this(config.payment().stripe().baseUrl(), config.payment().stripe().apiKey().orElse(""),
            config.payment().stripe().apiVersion(), config.payment().stripe().connectTimeout(),
            config.payment().stripe().readTimeout(), mapper);
    }

    public StripeApi(URI baseUrl, String apiKey, Optional<String> apiVersion, Duration connectTimeout,
                     Duration readTimeout, ObjectMapper mapper) {
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.apiVersion = apiVersion.filter(version -> !version.isBlank());
        this.readTimeout = readTimeout;
        this.mapper = mapper;
    }

    @Retry(maxRetries = 2, delay = 300, jitter = 150, retryOn = ProviderUnavailableException.class)
    @CircuitBreaker(requestVolumeThreshold = 6, failureRatio = 0.5, delay = 30_000,
        failOn = ProviderUnavailableException.class)
    public JsonNode post(String path, UUID idempotencyKey, Map<String, String> form) {
        return send(request(path)
            .header("Idempotency-Key", idempotencyKey.toString())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
            .build());
    }

    @Retry(maxRetries = 2, delay = 300, jitter = 150, retryOn = ProviderUnavailableException.class)
    @CircuitBreaker(requestVolumeThreshold = 6, failureRatio = 0.5, delay = 30_000,
        failOn = ProviderUnavailableException.class)
    public JsonNode get(String path) {
        return send(request(path).GET().build());
    }

    private HttpRequest.Builder request(String path) {
        if (apiKey.isBlank()) {
            throw new PaymentGatewayException("Chave de API da Stripe não configurada", false);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUrl.resolve(path))
            .timeout(readTimeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Accept", "application/json");
        apiVersion.ifPresent(version -> builder.header("Stripe-Version", version));
        return builder;
    }

    private JsonNode send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ProviderUnavailableException("falha de rede com a Stripe: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("chamada à Stripe interrompida", e);
        }
        int status = response.statusCode();
        if (status >= 500 || status == TOO_MANY_REQUESTS || status == CONFLICT) {
            throw new ProviderUnavailableException("Stripe respondeu " + status, null);
        }
        JsonNode body = parse(response.body());
        if (status >= 400) {
            String detail = body.path("error").path("message").asText("sem detalhe");
            throw new PaymentGatewayException("Stripe recusou (" + status + "): "
                + detail.replaceAll(SECRET_KEY_PATTERN, "***"), false);
        }
        return body;
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ProviderUnavailableException("resposta da Stripe não é JSON", e);
        }
    }

    static String encode(Map<String, String> form) {
        return form.entrySet().stream()
            .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    }

    /** Falha transitória: vale repetir (com a mesma chave de idempotência). */
    public static class ProviderUnavailableException extends PaymentGatewayException {

        public ProviderUnavailableException(String message, Throwable cause) {
            super(message, true, cause);
        }
    }
}
