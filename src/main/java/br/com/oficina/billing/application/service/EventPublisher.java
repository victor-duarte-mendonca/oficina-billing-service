package br.com.oficina.billing.application.service;

import br.com.oficina.billing.application.messaging.BillingMessageFactory;
import br.com.oficina.billing.application.ports.out.BillingMetricsPort;
import br.com.oficina.billing.application.ports.out.OutboxPort;
import br.com.oficina.billing.domain.event.BillingEvent;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Leva os eventos dos agregados para o outbox, dentro da transação de quem chama. */
public final class EventPublisher {

    private final OutboxPort outbox;
    private final BillingMessageFactory factory;
    private final BillingMetricsPort metrics;
    private final Clock clock;

    public EventPublisher(OutboxPort outbox, BillingMessageFactory factory, BillingMetricsPort metrics, Clock clock) {
        this.outbox = outbox;
        this.factory = factory;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** @param causationId comando de origem; {@code null} para API, webhook e jobs */
    public void publish(List<BillingEvent> events, UUID causationId) {
        events.forEach(event -> publish(event, causationId));
    }

    public void publish(BillingEvent event, UUID causationId) {
        outbox.enqueue(factory.from(event, causationId, OffsetDateTime.now(clock)));
        metrics.eventPublished(event.type());
    }
}
