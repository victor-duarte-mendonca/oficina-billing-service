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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.microprofile.faulttolerance.exceptions.FaultToleranceException;

/**
 * Adapter da Stripe: Pix por PaymentIntent em BRL, modo de teste (ADR 0023, spec 05 §4). Traduz o pedido de Pix e os
 * estados do PaymentIntent para o vocabulário do domínio.
 *
 * <p>Campos confirmados com um PaymentIntent real do sandbox em 2026-11-26: a cobrança nasce
 * {@code requires_action} com {@code next_action.pix_display_qr_code} ({@code data} = copia e cola,
 * {@code hosted_instructions_url}, {@code image_url_png}). A Stripe não devolve o QR em base64: {@code qrCodeBase64}
 * fica nulo e o cliente desenha o QR a partir do copia e cola.</p>
 */
@ApplicationScoped
@Typed(StripePaymentGateway.class)
public class StripePaymentGateway implements PaymentGatewayPort {

    static final long MIN_EXPIRY_SECONDS = 60;
    static final long MAX_EXPIRY_SECONDS = 1_209_600;
    private static final String ID_PATTERN = "pi_[A-Za-z0-9_]{1,64}";

    private final StripeApi api;
    private final String fallbackPayerEmail;
    private final Clock clock;

    @Inject
    public StripePaymentGateway(StripeApi api, BillingConfig config, Clock clock) {
        this(api, config.payment().stripe().fallbackPayerEmail(), clock);
    }

    public StripePaymentGateway(StripeApi api, String fallbackPayerEmail, Clock clock) {
        this.api = api;
        this.fallbackPayerEmail = fallbackPayerEmail;
        this.clock = clock;
    }

    @Override
    public PixCharge createPix(PixRequest request) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("amount", Long.toString(cents(request.amount())));
        form.put("currency", "brl");
        form.put("confirm", "true");
        form.put("automatic_payment_methods[enabled]", "true");
        form.put("automatic_payment_methods[allow_redirects]", "never");
        form.put("payment_method_data[type]", "pix");
        form.put("payment_method_data[billing_details][name]", "Cliente da oficina");
        form.put("payment_method_data[billing_details][email]", request.payerEmail().orElse(fallbackPayerEmail));
        form.put("payment_method_options[pix][expires_after_seconds]",
            Long.toString(expirySeconds(request.expiresAt())));
        form.put("description", request.description());
        form.put("metadata[paymentId]", request.paymentId().toString());

        JsonNode intent = guarded(() -> api.post("/v1/payment_intents", request.idempotencyKey(), form));
        JsonNode qr = intent.path("next_action").path("pix_display_qr_code");
        if (qr.isMissingNode()) {
            throw new PaymentGatewayException("Stripe não devolveu o QR Code do Pix (status="
                + intent.path("status").asText("?") + ")", false);
        }
        return new PixCharge(intent.path("id").asText(), text(qr, "data"), null, text(qr, "hosted_instructions_url"),
            qr.path("expires_at").canConvertToLong() && qr.path("expires_at").asLong() > 0
                ? Instant.ofEpochSecond(qr.path("expires_at").asLong()) : request.expiresAt());
    }

    @Override
    public ProviderPayment find(String providerPaymentId) {
        JsonNode intent = guarded(() -> api.get("/v1/payment_intents/" + validId(providerPaymentId)));
        String status = intent.path("status").asText("");
        String detail = intent.path("last_payment_error").path("code")
            .asText(intent.path("cancellation_reason").asText(status));
        return new ProviderPayment(providerPaymentId, map(status), detail);
    }

    @Override
    public RefundOutcome refund(String providerPaymentId, Money amount, UUID idempotencyKey) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("payment_intent", validId(providerPaymentId));
        form.put("amount", Long.toString(cents(amount)));
        JsonNode refund = guarded(() -> api.post("/v1/refunds", idempotencyKey, form));
        return mapRefund(refund.path("status").asText(""));
    }

    /** O estorno mais recente do PaymentIntent (a Stripe lista do mais novo para o mais antigo). */
    @Override
    public Optional<RefundOutcome> findRefund(String providerPaymentId) {
        JsonNode refunds = guarded(() -> api.get("/v1/refunds?limit=1&payment_intent=" + validId(providerPaymentId)));
        JsonNode latest = refunds.path("data").path(0);
        return latest.isMissingNode() ? Optional.empty() : Optional.of(mapRefund(latest.path("status").asText("")));
    }

    /**
     * Estados do PaymentIntent. Um Pix que expira ou falha volta a {@code requires_payment_method} (S5): não é mais
     * pagável e a Saga pede uma nova cobrança, então conta como {@code EXPIRED}. {@code canceled} idem.
     */
    static ProviderStatus map(String status) {
        return switch (status) {
            case "succeeded" -> ProviderStatus.APPROVED;
            case "canceled", "requires_payment_method" -> ProviderStatus.EXPIRED;
            default -> ProviderStatus.PENDING;
        };
    }

    /**
     * Estados do estorno. O Pix nasce {@code pending} e conclui em segundos (observado no sandbox em 2026-11-26);
     * {@code failed} e {@code canceled} são definitivos; estado desconhecido espera (nunca assume sucesso).
     */
    static RefundOutcome mapRefund(String status) {
        return switch (status) {
            case "succeeded" -> RefundOutcome.SUCCEEDED;
            case "failed", "canceled" -> RefundOutcome.FAILED;
            default -> RefundOutcome.PENDING;
        };
    }

    /** Valor em centavos (BRL tem 2 casas); {@code Money} já normaliza para 2 casas. */
    static long cents(Money amount) {
        return amount.amount().movePointRight(2).longValueExact();
    }

    long expirySeconds(Instant expiresAt) {
        long seconds = Duration.between(clock.instant(), expiresAt).getSeconds();
        return Math.max(MIN_EXPIRY_SECONDS, Math.min(MAX_EXPIRY_SECONDS, seconds));
    }

    /** O id do PaymentIntent é {@code pi_…}; qualquer outra coisa não chega à URL (sem injeção de caminho). */
    static String validId(String providerPaymentId) {
        if (providerPaymentId == null || !providerPaymentId.matches(ID_PATTERN)) {
            throw new PaymentGatewayException("Identificador de pagamento inválido", false);
        }
        return providerPaymentId;
    }

    private static JsonNode guarded(Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (FaultToleranceException e) {
            throw new PaymentGatewayException("Stripe indisponível (" + e.getClass().getSimpleName() + ")", true, e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
