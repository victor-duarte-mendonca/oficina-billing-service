package br.com.oficina.billing.infrastructure.messaging;

import br.com.oficina.billing.infrastructure.persistence.OutboxMessageEntity;
import br.com.oficina.billing.infrastructure.persistence.OutboxRepositoryAdapter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import org.jboss.logging.Logger;

/**
 * Publica um lote do outbox numa transação. Ao primeiro erro o lote para: respostas seguintes da mesma OS não podem
 * passar à frente da que falhou. Uma queda entre o envio e o commit republica a mensagem, e a deduplicação do SQS
 * FIFO (mesmo {@code messageId}) evita a cópia.
 */
@ApplicationScoped
public class OutboxPublisher {

    private static final Logger LOG = Logger.getLogger(OutboxPublisher.class);

    private final OutboxRepositoryAdapter outbox;
    private final MessageTransport transport;

    public OutboxPublisher(OutboxRepositoryAdapter outbox, MessageTransport transport) {
        this.outbox = outbox;
        this.transport = transport;
    }

    /** @return quantas mensagens foram publicadas neste lote */
    @Transactional
    public int publishBatch(int limit) {
        int published = 0;
        for (OutboxMessageEntity message : outbox.lockPending(limit)) {
            try {
                transport.sendToOrderService(message.getMessageGroupId(), message.getId(), message.getPayload());
                message.markPublished(OffsetDateTime.now());
                published++;
            } catch (RuntimeException e) {
                message.markFailed(e.getMessage());
                LOG.warnf(e, "Falha ao publicar %s (%s); nova tentativa no próximo ciclo",
                    message.getId(), message.getMessageType());
                break;
            }
        }
        return published;
    }
}
