package br.com.oficina.billing.infrastructure.configuration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Configuração de infraestrutura ({@code app.*}). URLs de filas vêm do contrato SSM da spec 12; a chave de API e o
 * segredo do webhook da Stripe vêm do SSM Parameter Store (SecureString) como variáveis do pod — nunca do
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
        /** {@code simulated} (dev/hml/testes) ou {@code stripe}. */
        @WithDefault("simulated")
        Provider provider();

        /** Validade do Pix (a Stripe aceita de 60 s a 14 dias). */
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

        Stripe stripe();

        enum Provider { SIMULATED, STRIPE }

        interface Stripe {
            @WithDefault("https://api.stripe.com")
            URI baseUrl();

            /** Chave de API de <b>teste</b> restrita (`rk_test_…`). Obrigatória com o provedor {@code stripe}. */
            Optional<String> apiKey();

            /** Segredo do endpoint de webhook (`whsec_…`) usado para verificar {@code Stripe-Signature}. */
            Optional<String> webhookSecret();

            /** Versão da API fixada em {@code Stripe-Version}; vazio usa a padrão da conta (S7). */
            Optional<String> apiVersion();

            /** O Pix exige e-mail do pagador; clientes sem e-mail usam este. */
            @WithDefault("cliente-sem-email@oficina.local")
            String fallbackPayerEmail();

            @WithDefault("PT3S")
            Duration connectTimeout();

            @WithDefault("PT10S")
            Duration readTimeout();

            /** Diferença máxima entre o relógio e o {@code t} da assinatura (anti-<i>replay</i>). */
            @WithDefault("PT5M")
            Duration webhookTolerance();
        }
    }
}
