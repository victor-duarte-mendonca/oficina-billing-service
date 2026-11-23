package br.com.oficina.billing.domain.model;

/**
 * Situação do pagamento segundo o provedor, já traduzida pelo adapter (o domínio não conhece os códigos do
 * Mercado Pago).
 */
public enum ProviderStatus {
    PENDING, APPROVED, REJECTED, EXPIRED
}
