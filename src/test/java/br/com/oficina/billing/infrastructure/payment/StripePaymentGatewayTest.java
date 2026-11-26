package br.com.oficina.billing.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.oficina.billing.application.ports.out.PaymentGatewayPort.PaymentGatewayException;
import br.com.oficina.billing.application.ports.out.PaymentGatewayPort.PixRequest;
import br.com.oficina.billing.application.ports.out.PaymentGatewayPort.ProviderPayment;
import br.com.oficina.billing.domain.model.PixCharge;
import br.com.oficina.billing.domain.model.ProviderStatus;
import br.com.oficina.billing.domain.valueobject.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contrato do adapter com a API da Stripe, contra um servidor HTTP local que responde no formato obtido de um
 * PaymentIntent Pix real do sandbox (2026-11-26, spec 05 §4): corpo form-urlencoded, cabeçalhos, QR Code, estados,
 * centavos e classificação de erros.
 */
class StripePaymentGatewayTest {

    private static final String KEY = "rk_test_chave_de_exemplo";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody = "{}";
    private StripePaymentGateway gateway;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        StripeApi api = new StripeApi(base, KEY, Optional.of("2026-09-30.preview"), Duration.ofSeconds(2),
            Duration.ofSeconds(2), MAPPER);
        gateway = new StripePaymentGateway(api, "sem-email@oficina.local", CLOCK);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void createPix_shouldSendTheFormAndReadTheQrCode() {
        responseBody = """
            {"id": "pi_3Ug0w6PktTZwDP3d1MxOqt7I", "object": "payment_intent", "status": "requires_action",
             "next_action": {"type": "pix_display_qr_code", "pix_display_qr_code": {
               "data": "123456789br.gov.bcb.pixapi.developer.stripe.com/v1/p/v2/1234567890abcdef/789",
               "expires_at": 1795693438, "hosted_instructions_url": "https://payments.stripe.com/qr/instructions/abc",
               "image_url_png": "https://qr.stripe.com/test_x.png"}}}
            """;
        UUID key = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        PixCharge charge = gateway.createPix(new PixRequest(key, paymentId, Money.of("450.00"), "Oficina — OS-000001",
            Optional.empty(), NOW.plus(Duration.ofMinutes(30))));

        assertThat(charge.providerPaymentId()).isEqualTo("pi_3Ug0w6PktTZwDP3d1MxOqt7I");
        assertThat(charge.qrCode()).startsWith("123456789br.gov.bcb.pix");
        assertThat(charge.qrCodeBase64()).isNull();
        assertThat(charge.ticketUrl()).isEqualTo("https://payments.stripe.com/qr/instructions/abc");
        assertThat(charge.expiresAt()).isEqualTo(Instant.ofEpochSecond(1795693438L));

        Recorded sent = requests.get(0);
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo("/v1/payment_intents");
        assertThat(sent.headers()).containsEntry("Authorization", "Bearer " + KEY)
            .containsEntry("Idempotency-key", key.toString())
            .containsEntry("Stripe-version", "2026-09-30.preview")
            .containsEntry("Content-type", "application/x-www-form-urlencoded");
        Map<String, String> form = sent.form();
        assertThat(form).containsEntry("amount", "45000").containsEntry("currency", "brl")
            .containsEntry("confirm", "true")
            .containsEntry("automatic_payment_methods[enabled]", "true")
            .containsEntry("automatic_payment_methods[allow_redirects]", "never")
            .containsEntry("payment_method_data[type]", "pix")
            .containsEntry("payment_method_data[billing_details][email]", "sem-email@oficina.local")
            .containsEntry("payment_method_options[pix][expires_after_seconds]", "1800")
            .containsEntry("metadata[paymentId]", paymentId.toString())
            .doesNotContainKey("payment_method_types");
    }

    @Test
    void createPix_withoutQrCode_shouldFailWithoutRetrying() {
        responseBody = "{\"id\": \"pi_1\", \"status\": \"requires_payment_method\"}";

        assertThatThrownBy(() -> gateway.createPix(new PixRequest(UUID.randomUUID(), UUID.randomUUID(),
            Money.of("1.00"), "x", Optional.of("ana@x.com"), NOW.plusSeconds(600))))
            .isInstanceOfSatisfying(PaymentGatewayException.class, e -> assertThat(e.isTransient()).isFalse());
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).form()).containsEntry("payment_method_data[billing_details][email]", "ana@x.com");
    }

    @Test
    void expiry_shouldStayWithinWhatStripeAccepts() {
        assertThat(gateway.expirySeconds(NOW.plusSeconds(5))).isEqualTo(StripePaymentGateway.MIN_EXPIRY_SECONDS);
        assertThat(gateway.expirySeconds(NOW.minusSeconds(100))).isEqualTo(StripePaymentGateway.MIN_EXPIRY_SECONDS);
        assertThat(gateway.expirySeconds(NOW.plusSeconds(600))).isEqualTo(600);
        assertThat(gateway.expirySeconds(NOW.plus(Duration.ofDays(30)))).isEqualTo(StripePaymentGateway.MAX_EXPIRY_SECONDS);
    }

    @Test
    void amounts_shouldBeSentInCents() {
        assertThat(StripePaymentGateway.cents(Money.of("0.50"))).isEqualTo(50);
        assertThat(StripePaymentGateway.cents(Money.of("450.00"))).isEqualTo(45_000);
        assertThat(StripePaymentGateway.cents(Money.of("1234.56"))).isEqualTo(123_456);
    }

    @Test
    void find_shouldTranslateTheIntentStatus() {
        responseBody = "{\"id\": \"pi_55\", \"status\": \"succeeded\"}";

        ProviderPayment payment = gateway.find("pi_55");

        assertThat(payment.status()).isEqualTo(ProviderStatus.APPROVED);
        assertThat(payment.detail()).isEqualTo("succeeded");
        assertThat(requests.get(0).path()).isEqualTo("/v1/payment_intents/pi_55");
        assertThat(requests.get(0).method()).isEqualTo("GET");
    }

    @Test
    void find_shouldReportTheFailureCodeWhenThereIsOne() {
        responseBody = "{\"id\": \"pi_55\", \"status\": \"requires_payment_method\","
            + " \"last_payment_error\": {\"code\": \"payment_intent_payment_attempt_failed\"}}";

        ProviderPayment payment = gateway.find("pi_55");

        assertThat(payment.status()).isEqualTo(ProviderStatus.EXPIRED);
        assertThat(payment.detail()).isEqualTo("payment_intent_payment_attempt_failed");
    }

    @Test
    void statusMapping_shouldCoverTheIntentLifecycle() {
        assertThat(StripePaymentGateway.map("succeeded")).isEqualTo(ProviderStatus.APPROVED);
        assertThat(StripePaymentGateway.map("requires_action")).isEqualTo(ProviderStatus.PENDING);
        assertThat(StripePaymentGateway.map("processing")).isEqualTo(ProviderStatus.PENDING);
        assertThat(StripePaymentGateway.map("requires_confirmation")).isEqualTo(ProviderStatus.PENDING);
        assertThat(StripePaymentGateway.map("requires_payment_method")).isEqualTo(ProviderStatus.EXPIRED);
        assertThat(StripePaymentGateway.map("canceled")).isEqualTo(ProviderStatus.EXPIRED);
    }

    @Test
    void refund_shouldPostTheRefundWithTheIdempotencyKey() {
        UUID key = UUID.randomUUID();
        responseBody = "{\"id\": \"re_1\", \"status\": \"succeeded\"}";

        gateway.refund("pi_55", Money.of("450.00"), key);

        Recorded sent = requests.get(0);
        assertThat(sent.path()).isEqualTo("/v1/refunds");
        assertThat(sent.headers()).containsEntry("Idempotency-key", key.toString());
        assertThat(sent.form()).containsEntry("payment_intent", "pi_55").containsEntry("amount", "45000");
    }

    @Test
    void providerRefusal_shouldNotBeRetried_andNeverLeakTheKey() {
        status = 400;
        responseBody = "{\"error\": {\"message\": \"Invalid API Key provided: rk_test_abcDEF123\","
            + " \"type\": \"invalid_request_error\"}}";

        assertThatThrownBy(() -> gateway.refund("pi_55", Money.of("1.00"), UUID.randomUUID()))
            .isInstanceOfSatisfying(PaymentGatewayException.class, e -> {
                assertThat(e.isTransient()).isFalse();
                assertThat(e.getMessage()).contains("400").contains("Invalid API Key")
                    .doesNotContain("abcDEF123").doesNotContain(KEY);
            });
        assertThat(requests).hasSize(1);
    }

    @Test
    void serverErrors_rateLimitsAndIdempotencyConflicts_shouldBeTransient() {
        for (int code : new int[] {503, 429, 409}) {
            status = code;
            assertThatThrownBy(() -> gateway.find("pi_55"))
                .isInstanceOfSatisfying(PaymentGatewayException.class, e -> assertThat(e.isTransient()).isTrue());
        }

        status = 200;
        responseBody = "<html>proxy</html>";
        assertThatThrownBy(() -> gateway.find("pi_55"))
            .isInstanceOfSatisfying(PaymentGatewayException.class, e -> assertThat(e.isTransient()).isTrue());
    }

    @Test
    void unreachableProvider_shouldBeTransient() {
        server.stop(0);

        assertThatThrownBy(() -> gateway.find("pi_55"))
            .isInstanceOfSatisfying(PaymentGatewayException.class, e -> assertThat(e.isTransient()).isTrue());
    }

    @Test
    void providerIds_shouldBePaymentIntentIdsBeforeReachingTheUrl() {
        assertThatThrownBy(() -> gateway.find("../../v1/account")).isInstanceOf(PaymentGatewayException.class);
        assertThatThrownBy(() -> gateway.find("123456")).isInstanceOf(PaymentGatewayException.class);
        assertThatThrownBy(() -> gateway.find(null)).isInstanceOf(PaymentGatewayException.class);
        assertThatThrownBy(() -> gateway.refund("pi_1/../x", Money.of("1.00"), UUID.randomUUID()))
            .isInstanceOf(PaymentGatewayException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void missingApiKey_shouldFailWithoutCallingTheProvider() {
        StripeApi api = new StripeApi(URI.create("http://127.0.0.1:1"), "", Optional.empty(), Duration.ofSeconds(1),
            Duration.ofSeconds(1), MAPPER);
        StripePaymentGateway unconfigured = new StripePaymentGateway(api, "x@x.com", CLOCK);

        assertThatThrownBy(() -> unconfigured.find("pi_1"))
            .isInstanceOfSatisfying(PaymentGatewayException.class, e -> assertThat(e.isTransient()).isFalse());
    }

    private void answer(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.get(0)));
        requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body));
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private record Recorded(String method, String path, Map<String, String> headers, String body) {

        Map<String, String> form() {
            Map<String, String> fields = new LinkedHashMap<>();
            for (String pair : body.split("&")) {
                String[] keyValue = pair.split("=", 2);
                fields.put(URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8),
                    keyValue.length > 1 ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8) : "");
            }
            return fields;
        }
    }
}
