package br.com.oficina.billing.application.ports.out;

import br.com.oficina.billing.application.messaging.OutboundMessage;

/**
 * Enfileira a resposta <b>na mesma transação</b> da mudança de estado (Transactional Outbox): se a transação desfaz,
 * a mensagem nunca existiu; se confirma, o relay a publica.
 */
public interface OutboxPort {

    void enqueue(OutboundMessage message);
}
