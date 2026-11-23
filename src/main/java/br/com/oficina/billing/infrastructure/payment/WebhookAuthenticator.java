package br.com.oficina.billing.infrastructure.payment;

import br.com.oficina.billing.infrastructure.configuration.BillingConfig;
import br.com.oficina.billing.infrastructure.configuration.BillingConfig.Payment.Provider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Decide se uma notificação recebida pode ser processada. Com o Mercado Pago real, o segredo é obrigatório
 * (<i>fail closed</i>: sem segredo configurado, toda notificação é recusada). Com o provedor simulado, a assinatura
 * só é exigida se houver segredo — o que permite testar a rota com e sem ele.
 */
@ApplicationScoped
public class WebhookAuthenticator {

    private final Optional<MercadoPagoSignature> signature;
    private final boolean required;

    @Inject
    public WebhookAuthenticator(BillingConfig config) {
        this(config.payment().mercadopago().webhookSecret(), config.payment().provider() == Provider.MERCADOPAGO);
    }

    public WebhookAuthenticator(Optional<String> secret, boolean required) {
        this.signature = secret.filter(value -> !value.isBlank()).map(MercadoPagoSignature::new);
        this.required = required;
    }

    public boolean accepts(String signatureHeader, String requestId, String dataId) {
        return signature.map(verifier -> verifier.isValid(signatureHeader, requestId, dataId)).orElse(!required);
    }
}
