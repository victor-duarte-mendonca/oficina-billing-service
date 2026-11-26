package br.com.oficina.billing.infrastructure.configuration;

import br.com.oficina.billing.application.ports.out.PaymentGatewayPort;
import br.com.oficina.billing.infrastructure.configuration.BillingConfig.Payment.Provider;
import br.com.oficina.billing.infrastructure.payment.StripePaymentGateway;
import br.com.oficina.billing.infrastructure.payment.SimulatedPaymentGateway;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;
import org.jboss.logging.Logger;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * Relógio, cliente SQS e a escolha do provedor de pagamento (Strategy por configuração). Credenciais AWS pela cadeia
 * padrão do SDK (IMDS no k3s, ADR 0022); nenhuma chave no código.
 */
@ApplicationScoped
public class InfrastructureBeans {

    private static final Logger LOG = Logger.getLogger(InfrastructureBeans.class);

    @Produces
    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }

    @Produces
    @ApplicationScoped
    PaymentGatewayPort paymentGateway(BillingConfig config, Instance<SimulatedPaymentGateway> simulated,
                                      Instance<StripePaymentGateway> stripe) {
        Provider provider = config.payment().provider();
        LOG.infof("Provedor de pagamento: %s", provider);
        return provider == Provider.STRIPE ? stripe.get() : simulated.get();
    }

    @Produces
    @ApplicationScoped
    SqsClient sqs(BillingConfig config) {
        SqsClientBuilder builder = SqsClient.builder()
            .region(Region.of(config.aws().region()))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClient(UrlConnectionHttpClient.create());
        config.aws().endpointOverride().ifPresent(builder::endpointOverride);
        return builder.build();
    }

    void closeSqs(@Disposes SqsClient client) {
        client.close();
    }
}
