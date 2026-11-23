package br.com.oficina.billing.application.ports.out;

/** Métricas de negócio do Billing (spec 13): o adapter Micrometer as expõe em {@code /q/metrics}. */
public interface BillingMetricsPort {

    /** Mensagem publicada no outbox, por tipo ({@code billing_events_total{type}}). */
    void eventPublished(String type);

    /** Falha ao falar com o provedor, por operação ({@code billing_gateway_failures_total{operation}}). */
    void gatewayFailure(String operation);

    /** Webhook recusado na verificação de assinatura ({@code billing_webhook_invalid_signature_total}). */
    void webhookRejected();
}
