package br.com.oficina.billing.infrastructure.payment;

import br.com.oficina.billing.infrastructure.configuration.BillingConfig;
import br.com.oficina.billing.infrastructure.configuration.BillingConfig.Payment.Provider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Decide se uma notificação recebida pode ser processada. Com a Stripe real, o segredo é obrigatório
 * (<i>fail closed</i>: sem segredo configurado, toda notificação é recusada). Com o provedor simulado, a assinatura
 * só é exigida se houver segredo — o que permite testar a rota com e sem ele.
 */
@ApplicationScoped
public class WebhookAuthenticator {

    private final Optional<StripeSignature> signature;
    private final boolean required;

    @Inject
    public WebhookAuthenticator(BillingConfig config, Clock clock) {
        this(config.payment().stripe().webhookSecret(), config.payment().provider() == Provider.STRIPE,
            config.payment().stripe().webhookTolerance(), clock);
    }

    public WebhookAuthenticator(Optional<String> secret, boolean required, Duration tolerance, Clock clock) {
        this.signature = secret.filter(value -> !value.isBlank())
            .map(value -> new StripeSignature(value, tolerance, clock));
        this.required = required;
    }

    public boolean accepts(String signatureHeader, String rawBody) {
        return signature.map(verifier -> verifier.isValid(signatureHeader, rawBody)).orElse(!required);
    }
}
