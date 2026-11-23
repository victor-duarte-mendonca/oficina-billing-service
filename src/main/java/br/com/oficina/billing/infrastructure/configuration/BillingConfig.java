package br.com.oficina.billing.infrastructure.configuration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Configuração de infraestrutura ({@code app.*}). URLs de filas vêm do contrato SSM da spec 12; o access token e o
 * segredo do webhook do Mercado Pago vêm do SSM Parameter Store (SecureString) como variáveis do pod — nunca do
 * repositório.
 */
@ConfigMapping(prefix = "app")
public interface BillingConfig {

    Aws aws();

    Messaging messaging();

    Gateway gateway();

    Payment payment();

    Budget budget();

    interface Gateway {
        /** Chave que o API Gateway injeta em {@code X-Gateway-Key}; vazia desliga o filtro (dev/test/docker). */
        Optional<String> key();
    }

    interface Aws {
        @WithDefault("us-east-1")
        String region();

        /** LocalStack em dev/teste; vazio = endpoints públicos da AWS. */
        Optional<URI> endpointOverride();
    }

    interface Messaging {
        @WithDefault("false")
        boolean enabled();

        Queues queues();

        @WithDefault("10")
        int maxMessages();

        @WithDefault("10")
        int waitSeconds();

        @WithDefault("25")
        int relayBatchSize();

        interface Queues {
            /** Fila de entrada: {@code billing-commands.fifo}. */
            Optional<String> commands();

            /** Fila de saída: {@code os-saga-replies.fifo}. */
            Optional<String> osReplies();
        }
    }

    interface Budget {
        /** Link enviado no e-mail; o token vai como parâmetro para a página que chama {@code /public/budgets}. */
        @WithDefault("http://localhost:8080/public/budgets")
        String publicApprovalUrl();
    }

    interface Payment {
        /** {@code simulated} (dev/hml/testes) ou {@code mercadopago}. */
        @WithDefault("simulated")
        Provider provider();

        /** Validade do Pix (o Mercado Pago aceita de 30 min a 30 dias). */
        @WithDefault("PT30M")
        Duration pixTtl();

        /** Idade mínima de um Pix pendente para a reconciliação consultá-lo. */
        @WithDefault("PT2M")
        Duration reconcileAfter();

        /** Pedido sem resposta do provedor por mais que isso é encerrado como falha. */
        @WithDefault("PT5M")
        Duration requestTimeout();

        /** Rota de simulação ({@code /admin/payments/{id}/simulate}); nunca ligada em produção. */
        @WithDefault("false")
        boolean simulationEnabled();

        MercadoPago mercadopago();

        enum Provider { SIMULATED, MERCADOPAGO }

        interface MercadoPago {
            @WithDefault("https://api.mercadopago.com")
            URI baseUrl();

            /** Access token de <b>teste</b> (usuário). Obrigatório com o provedor {@code mercadopago}. */
            Optional<String> accessToken();

            /** Segredo da assinatura do webhook ({@code x-signature}). */
            Optional<String> webhookSecret();

            /** URL pública do webhook informada em cada pagamento (rota do API Gateway). */
            Optional<String> notificationUrl();

            /** O Pix exige e-mail do pagador; clientes sem e-mail usam este. */
            @WithDefault("cliente-sem-email@oficina.local")
            String fallbackPayerEmail();

            @WithDefault("PT3S")
            Duration connectTimeout();

            @WithDefault("PT10S")
            Duration readTimeout();
        }
    }
}
