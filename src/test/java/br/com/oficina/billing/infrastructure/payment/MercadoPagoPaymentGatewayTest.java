package br.com.oficina.billing.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.oficina.billing.application.ports.out.PaymentGatewayPort.PaymentGatewayException;
import br.com.oficina.billing.application.ports.out.PaymentGatewayPort.PixRequest;
import br.com.oficina.billing.application.ports.out.PaymentGatewayPort.ProviderPayment;
import br.com.oficina.billing.domain.model.PixCharge;
import br.com.oficina.billing.domain.model.ProviderStatus;
import br.com.oficina.billing.domain.valueobject.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contrato do adapter com a Payments API, contra um servidor HTTP local que responde no formato documentado
 * (spec 05 §4). Prova os cabeçalhos (token, idempotência), o corpo do Pix, a leitura do QR Code, o mapeamento de
 * situações e a classificação de erros. <b>Pendente:</b> regravar as respostas com o sandbox (credenciais do usuário).
 */
class MercadoPagoPaymentGatewayTest {

    private static final String TOKEN = "TEST-token-de-exemplo";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 201;
    private volatile String responseBody = "{}";
    private MercadoPagoPaymentGateway gateway;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        MercadoPagoApi api = new MercadoPagoApi(base, TOKEN, Duration.ofSeconds(2), Duration.ofSeconds(2), MAPPER);
        gateway = new MercadoPagoPaymentGateway(api, Optional.of("https://api.oficina.example/webhooks/mercadopago"),
            "sem-email@oficina.local");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void createPix_shouldSendTheDocumentedRequestAndReadTheQrCode() throws IOException {
        responseBody = """
            {"id": 1312345678, "status": "pending", "status_detail": "pending_waiting_transfer",
             "date_of_expiration": "2026-11-23T11:30:00.000-03:00",
             "point_of_interaction": {"transaction_data": {"qr_code": "00020126...6304ABCD",
               "qr_code_base64": "iVBORw0KGgo=", "ticket_url": "https://www.mercadopago.com.br/payments/1312345678/ticket"}}}
            """;
        UUID key = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        PixCharge charge = gateway.createPix(new PixRequest(key, paymentId, Money.of("450.00"), "Oficina — OS-000001",
            Optional.empty(), Instant.parse("2026-11-23T14:30:00Z")));

        assertThat(charge.providerPaymentId()).isEqualTo("1312345678");
        assertThat(charge.qrCode()).startsWith("00020126");
        assertThat(charge.qrCodeBase64()).isEqualTo("iVBORw0KGgo=");
        assertThat(charge.ticketUrl()).contains("/ticket");
        assertThat(charge.expiresAt()).isEqualTo(Instant.parse("2026-11-23T14:30:00Z"));

        Recorded sent = requests.get(0);
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo("/v1/payments");
        assertThat(sent.headers()).containsEntry("Authorization", "Bearer " + TOKEN)
            .containsEntry("X-idempotency-key", key.toString());
        JsonNode body = MAPPER.readTree(sent.body());
        assertThat(body.get("transaction_amount").decimalValue()).isEqualByComparingTo("450.00");
        assertThat(body.get("payment_method_id").asText()).isEqualTo("pix");
        assertThat(body.get("external_reference").asText()).isEqualTo(paymentId.toString());
        assertThat(body.get("date_of_expiration").asText()).isEqualTo("2026-11-23T14:30:00.000Z");
        assertThat(body.at("/payer/email").asText()).isEqualTo("sem-email@oficina.local");
        assertThat(body.get("notification_url").asText()).endsWith("/webhooks/mercadopago");
    }

    @Test
    void createPix_withoutExpirationInTheAnswer_shouldKeepTheRequestedOne() {
        responseBody = "{\"id\": 77, \"status\": \"pending\"}";
        Instant requested = Instant.parse("2026-11-23T14:30:00Z");

        PixCharge charge = gateway.createPix(new PixRequest(UUID.randomUUID(), UUID.randomUUID(), Money.of("1.00"),
            "x", Optional.of("ana@x.com"), requested));

        assertThat(charge.expiresAt()).isEqualTo(requested);
        assertThat(charge.qrCode()).isNull();
    }

    @Test
    void find_shouldTranslateTheProviderStatus() {
        status = 200;
        responseBody = "{\"id\": 55, \"status\": \"approved\", \"status_detail\": \"accredited\"}";

        ProviderPayment payment = gateway.find("55");

        assertThat(payment.status()).isEqualTo(ProviderStatus.APPROVED);
        assertThat(payment.detail()).isEqualTo("accredited");
        assertThat(requests.get(0).path()).isEqualTo("/v1/payments/55");
        assertThat(requests.get(0).method()).isEqualTo("GET");
    }

    @Test
    void statusMapping_shouldCoverTheDocumentedValues() {
        assertThat(MercadoPagoPaymentGateway.map("approved")).isEqualTo(ProviderStatus.APPROVED);
        assertThat(MercadoPagoPaymentGateway.map("pending")).isEqualTo(ProviderStatus.PENDING);
        assertThat(MercadoPagoPaymentGateway.map("in_process")).isEqualTo(ProviderStatus.PENDING);
        assertThat(MercadoPagoPaymentGateway.map("rejected")).isEqualTo(ProviderStatus.REJECTED);
        assertThat(MercadoPagoPaymentGateway.map("refunded")).isEqualTo(ProviderStatus.REJECTED);
        assertThat(MercadoPagoPaymentGateway.map("cancelled")).isEqualTo(ProviderStatus.EXPIRED);
        assertThat(MercadoPagoPaymentGateway.map("expired")).isEqualTo(ProviderStatus.EXPIRED);
    }

    @Test
    void refund_shouldPostWithTheIdempotencyKey() throws IOException {
        UUID key = UUID.randomUUID();

        gateway.refund("55", Money.of("450.00"), key);

        Recorded sent = requests.get(0);
        assertThat(sent.path()).isEqualTo("/v1/payments/55/refunds");
        assertThat(sent.headers()).containsEntry("X-idempotency-key", key.toString());
        assertThat(MAPPER.readTree(sent.body()).get("amount").decimalValue()).isEqualByComparingTo("450.00");
    }

    @Test
    void providerRefusal_shouldNotBeRetried() {
        status = 400;
        responseBody = "{\"message\": \"Insufficient balance\", \"error\": \"bad_request\"}";

        assertThatThrownBy(() -> gateway.refund("55", Money.of("1.00"), UUID.randomUUID()))
            .isInstanceOfSatisfying(PaymentGatewayException.class, e -> {
                assertThat(e.isTransient()).isFalse();
                assertThat(e.getMessage()).contains("400").contains("Insufficient balance").doesNotContain(TOKEN);
            });
    }

    @Test
    void serverErrors_shouldBeTransient() {
        status = 503;
        assertThatThrownBy(() -> gateway.find("55"))
            .isInstanceOfSatisfying(PaymentGatewayException.class, e -> assertThat(e.isTransient()).isTrue());

        status = 200;
        responseBody = "<html>proxy</html>";
        assertThatThrownBy(() -> gateway.find("55"))
            .isInstanceOfSatisfying(PaymentGatewayException.class, e -> assertThat(e.isTransient()).isTrue());
    }

    @Test
    void unreachableProvider_shouldBeTransient() {
        server.stop(0);

        assertThatThrownBy(() -> gateway.find("55"))
            .isInstanceOfSatisfying(PaymentGatewayException.class, e -> assertThat(e.isTransient()).isTrue());
    }

    @Test
    void providerIds_shouldBeNumericBeforeReachingTheUrl() {
        assertThatThrownBy(() -> gateway.find("../../v1/account")).isInstanceOf(PaymentGatewayException.class);
        assertThatThrownBy(() -> gateway.find(null)).isInstanceOf(PaymentGatewayException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void missingAccessToken_shouldFailWithoutCallingTheProvider() {
        MercadoPagoApi api = new MercadoPagoApi(URI.create("http://127.0.0.1:1"), "", Duration.ofSeconds(1),
            Duration.ofSeconds(1), MAPPER);
        MercadoPagoPaymentGateway unconfigured = new MercadoPagoPaymentGateway(api, Optional.empty(), "x@x.com");

        assertThatThrownBy(() -> unconfigured.find("1"))
            .isInstanceOfSatisfying(PaymentGatewayException.class, e -> assertThat(e.isTransient()).isFalse());
    }

    private void answer(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new java.util.HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.get(0)));
        requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body));
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private record Recorded(String method, String path, Map<String, String> headers, String body) {
    }
}
