package br.com.oficina.billing.domain.model;

/**
 * Situação do pagamento segundo o provedor, já traduzida pelo adapter (o domínio não conhece os estados da
 * Stripe).
 */
public enum ProviderStatus {
    PENDING, APPROVED, REJECTED, EXPIRED
}
