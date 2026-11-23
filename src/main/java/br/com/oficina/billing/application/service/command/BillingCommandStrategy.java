package br.com.oficina.billing.application.service.command;

import br.com.oficina.billing.application.messaging.InboundMessage;

/**
 * Um tratador por comando da Saga (Strategy). Cada estratégia é dona das próprias fronteiras transacionais: as que
 * falam com o provedor de pagamento fazem a chamada fora da transação.
 */
public interface BillingCommandStrategy {

    /** Tipo do comando no contrato ({@code GenerateBudget}, {@code RequestPayment}, ...). */
    String type();

    void handle(InboundMessage command);
}
