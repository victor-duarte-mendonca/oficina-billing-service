package br.com.oficina.billing.application.ports.in;

import br.com.oficina.billing.application.messaging.InboundMessage;

/** Processa um comando da Saga vindo da fila {@code billing-commands}. */
public interface HandleBillingCommandUseCase {

    void handle(InboundMessage command);
}
