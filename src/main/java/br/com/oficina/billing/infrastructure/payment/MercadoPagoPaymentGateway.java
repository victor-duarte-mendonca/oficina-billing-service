package br.com.oficina.billing.infrastructure.payment;

import br.com.oficina.billing.application.ports.out.PaymentGatewayPort;
import br.com.oficina.billing.domain.model.PixCharge;
import br.com.oficina.billing.domain.model.ProviderStatus;
import br.com.oficina.billing.domain.valueobject.Money;
import br.com.oficina.billing.infrastructure.configuration.BillingConfig;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.microprofile.faulttolerance.exceptions.FaultToleranceException;

/**
 * Adapter do Mercado Pago (Payments API — ADR 0021, spec 05 §4). Traduz o pedido de Pix e os códigos de situação do
 * provedor para o vocabulário do domínio.
 *
 * <p><b>Não validado contra o sandbox</b> (credenciais de teste são do usuário): os campos seguem a documentação lida
 * em 2026-11-22/23; o teste de contrato usa respostas no formato documentado e deve ser regravado com o sandbox.</p>
 */
@ApplicationScoped
@Typed(MercadoPagoPaymentGateway.class)
public class MercadoPagoPaymentGateway implements PaymentGatewayPort {

    /** Formato de data aceito em {@code date_of_expiration} (com milissegundos e deslocamento). */
    static final DateTimeFormatter EXPIRATION = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final MercadoPagoApi api;
    private final Optional<String> notificationUrl;
    private final String fallbackPayerEmail;

    @Inject
    public MercadoPagoPaymentGateway(MercadoPagoApi api, BillingConfig config) {
        this(api, config.payment().mercadopago().notificationUrl(), config.payment().mercadopago().fallbackPayerEmail());
    }

    public MercadoPagoPaymentGateway(MercadoPagoApi api, Optional<String> notificationUrl, String fallbackPayerEmail) {
        this.api = api;
        this.notificationUrl = notificationUrl.filter(url -> !url.isBlank());
        this.fallbackPayerEmail = fallbackPayerEmail;
    }

    @Override
    public PixCharge createPix(PixRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transaction_amount", request.amount().amount());
        body.put("description", request.description());
        body.put("payment_method_id", "pix");
        body.put("external_reference", request.paymentId().toString());
        body.put("date_of_expiration", EXPIRATION.format(request.expiresAt().atOffset(ZoneOffset.UTC)));
        body.put("payer", Map.of("email", request.payerEmail().orElse(fallbackPayerEmail)));
        notificationUrl.ifPresent(url -> body.put("notification_url", url));

        JsonNode payment = guarded(() -> api.post("/v1/payments", request.idempotencyKey(), body));
        JsonNode transaction = payment.path("point_of_interaction").path("transaction_data");
        return new PixCharge(payment.path("id").asText(), text(transaction, "qr_code"),
            text(transaction, "qr_code_base64"), text(transaction, "ticket_url"),
            expiration(payment).orElse(request.expiresAt()));
    }

    @Override
    public ProviderPayment find(String providerPaymentId) {
        JsonNode payment = guarded(() -> api.get("/v1/payments/" + numeric(providerPaymentId)));
        String status = payment.path("status").asText("");
        String detail = payment.path("status_detail").asText(status);
        return new ProviderPayment(providerPaymentId, map(status), detail);
    }

    @Override
    public void refund(String providerPaymentId, Money amount, UUID idempotencyKey) {
        guarded(() -> api.post("/v1/payments/" + numeric(providerPaymentId) + "/refunds", idempotencyKey,
            Map.of("amount", amount.amount())));
    }

    /**
     * Situações da Payments API. Pix vencido chega como {@code cancelled} (V5: confirmar o {@code status_detail} no
     * sandbox); estornado ou contestado conta como "sem dinheiro com a oficina".
     */
    static ProviderStatus map(String status) {
        return switch (status) {
            case "approved" -> ProviderStatus.APPROVED;
            case "rejected", "refunded", "charged_back" -> ProviderStatus.REJECTED;
            case "cancelled", "expired" -> ProviderStatus.EXPIRED;
            default -> ProviderStatus.PENDING;
        };
    }

    /** O id do Mercado Pago é numérico; qualquer outra coisa não chega à URL (sem injeção de caminho). */
    static String numeric(String providerPaymentId) {
        if (providerPaymentId == null || !providerPaymentId.matches("\\d{1,20}")) {
            throw new PaymentGatewayException("Identificador de pagamento inválido", false);
        }
        return providerPaymentId;
    }

    private static JsonNode guarded(Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (FaultToleranceException e) {
            throw new PaymentGatewayException("Mercado Pago indisponível (" + e.getClass().getSimpleName() + ")", true, e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Optional<Instant> expiration(JsonNode payment) {
        String value = text(payment, "date_of_expiration");
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
