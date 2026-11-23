package br.com.oficina.billing.application.ports.out;

import java.util.UUID;

/**
 * Consumidor idempotente: o SQS entrega pelo menos uma vez. O registro é feito na transação que aplica o efeito;
 * se ela desfaz, a mensagem volta a ser "nova".
 */
public interface InboxPort {

    /** @return {@code true} na primeira vez; {@code false} se a mensagem já foi processada */
    boolean markProcessed(UUID messageId);

    /** Consulta sem registrar (usada antes de falar com o provedor, fora da transação final). */
    boolean wasProcessed(UUID messageId);
}
