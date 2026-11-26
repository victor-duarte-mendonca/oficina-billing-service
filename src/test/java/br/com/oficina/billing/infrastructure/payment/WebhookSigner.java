package br.com.oficina.billing.infrastructure.payment;

import java.time.Instant;

/** Assina eventos de teste como a Stripe faria (mesmo segredo do perfil de teste, relógio atual). */
public final class WebhookSigner {

    public static final String TEST_SECRET = "segredo-de-teste-do-webhook";

    private WebhookSigner() {
    }

    /** Cabeçalho {@code Stripe-Signature} válido agora para o corpo exato que será enviado. */
    public static String header(String rawBody) {
        return header(rawBody, Instant.now().getEpochSecond());
    }

    public static String header(String rawBody, long timestamp) {
        return "t=" + timestamp + ",v1=" + StripeSignature.sign(TEST_SECRET, timestamp, rawBody);
    }

    /** Evento mínimo no formato da Stripe: {@code type} e {@code data.object}. */
    public static String event(String type, String objectJson) {
        return "{\"id\":\"evt_test\",\"object\":\"event\",\"type\":\"" + type + "\",\"data\":{\"object\":" + objectJson + "}}";
    }

    public static String paymentIntentEvent(String type, String paymentIntentId) {
        return event(type, "{\"id\":\"" + paymentIntentId + "\",\"object\":\"payment_intent\"}");
    }
}
