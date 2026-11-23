package br.com.oficina.billing.infrastructure.payment;

/** Assina notificações de teste como o Mercado Pago faria (mesmo segredo do perfil de teste). */
public final class WebhookSigner {

    public static final String TEST_SECRET = "segredo-de-teste-do-webhook";

    private WebhookSigner() {
    }

    public static String header(String dataId, String requestId, long ts) {
        String v1 = new MercadoPagoSignature(TEST_SECRET).sign(MercadoPagoSignature.manifest(dataId, requestId,
            Long.toString(ts)));
        return "ts=" + ts + ",v1=" + v1;
    }
}
