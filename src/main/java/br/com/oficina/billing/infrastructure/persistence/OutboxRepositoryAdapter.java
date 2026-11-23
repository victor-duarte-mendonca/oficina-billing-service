package br.com.oficina.billing.infrastructure.persistence;

import br.com.oficina.billing.application.messaging.OutboundMessage;
import br.com.oficina.billing.application.ports.out.OutboxPort;
import br.com.oficina.billing.infrastructure.messaging.MessageEnvelopeCodec;
import br.com.oficina.billing.infrastructure.observability.RequestContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import java.util.List;

/**
 * Outbox no PostgreSQL. O envelope é serializado no enfileiramento, com a correlação e o trace da requisição em
 * curso — o relay publica depois, em outra thread, sem esse contexto.
 */
@ApplicationScoped
public class OutboxRepositoryAdapter implements OutboxPort {

    private final EntityManager em;
    private final MessageEnvelopeCodec codec;

    public OutboxRepositoryAdapter(EntityManager em, MessageEnvelopeCodec codec) {
        this.em = em;
        this.codec = codec;
    }

    @Override
    public void enqueue(OutboundMessage message) {
        String body = codec.encode(message, RequestContext.correlationId().orElse(null),
            RequestContext.traceparent().orElse(null));
        em.persist(new OutboxMessageEntity(message.messageId(), message.groupId(), message.type(), body,
            message.occurredAt()));
    }

    /** Próximas mensagens, travadas para esta transação; {@code SKIP LOCKED} deixa duas réplicas rodarem o relay. */
    @SuppressWarnings("unchecked")
    public List<OutboxMessageEntity> lockPending(int limit) {
        return em.createNativeQuery("SELECT * FROM outbox WHERE published_at IS NULL ORDER BY created_at, id "
                + "LIMIT :limit FOR UPDATE SKIP LOCKED", OutboxMessageEntity.class)
            .setParameter("limit", limit)
            .getResultList();
    }
}
