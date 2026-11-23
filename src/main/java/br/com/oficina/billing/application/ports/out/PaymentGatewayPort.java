package br.com.oficina.billing.application.ports.out;

import br.com.oficina.billing.domain.model.PixCharge;
import br.com.oficina.billing.domain.model.ProviderStatus;
import br.com.oficina.billing.domain.valueobject.Money;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Provedor de pagamento (ADR 0021). Implementações: Mercado Pago (produção), simulado (dev/hml e testes). Trocar de
 * provedor — ou da Payments API para a Orders API do Mercado Pago — não toca o domínio.
 */
public interface PaymentGatewayPort {

    /** Cria a cobrança Pix. A mesma {@code idempotencyKey} nunca gera duas cobranças. */
    PixCharge createPix(PixRequest request);

    /** Situação atual, consultada no provedor (fonte da verdade; o corpo do webhook nunca é usado). */
    ProviderPayment find(String providerPaymentId);

    /** Estorno total. A mesma {@code idempotencyKey} nunca estorna duas vezes. */
    void refund(String providerPaymentId, Money amount, UUID idempotencyKey);

    record PixRequest(UUID idempotencyKey, UUID paymentId, Money amount, String description,
                      Optional<String> payerEmail, Instant expiresAt) {
    }

    record ProviderPayment(String providerPaymentId, ProviderStatus status, String detail) {
    }

    /**
     * Falha ao falar com o provedor. {@code transientFailure}: rede, 5xx, circuito aberto — vale tentar de novo com
     * a mesma chave. Caso contrário o provedor recusou o pedido (4xx).
     */
    class PaymentGatewayException extends RuntimeException {

        private final boolean transientFailure;

        public PaymentGatewayException(String message, boolean transientFailure, Throwable cause) {
            super(message, cause);
            this.transientFailure = transientFailure;
        }

        public PaymentGatewayException(String message, boolean transientFailure) {
            this(message, transientFailure, null);
        }

        public boolean isTransient() {
            return transientFailure;
        }
    }
}
