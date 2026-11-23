package br.com.oficina.billing.infrastructure.observability;

import br.com.oficina.billing.application.ports.out.BillingMetricsPort;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;

/** Métricas do Billing em {@code /q/metrics} (nomes da spec 13). Rótulos de cardinalidade baixa e fixa. */
@ApplicationScoped
public class MicrometerBillingMetrics implements BillingMetricsPort {

    private final MeterRegistry registry;

    public MicrometerBillingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void eventPublished(String type) {
        registry.counter("billing_events_total", "type", type).increment();
    }

    @Override
    public void gatewayFailure(String operation) {
        registry.counter("billing_gateway_failures_total", "operation", operation).increment();
    }

    @Override
    public void webhookRejected() {
        registry.counter("billing_webhook_invalid_signature_total").increment();
    }
}
