package br.com.oficina.billing.application.service;

import br.com.oficina.billing.application.messaging.InboundMessage;
import br.com.oficina.billing.application.messaging.InvalidMessageException;
import br.com.oficina.billing.application.messaging.MessageKind;
import br.com.oficina.billing.application.ports.in.HandleBillingCommandUseCase;
import br.com.oficina.billing.application.service.command.BillingCommandStrategy;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Despacha cada comando para a sua estratégia. Tipo desconhecido é mensagem fora do contrato (vai para a DLQ). */
public final class BillingCommandHandler implements HandleBillingCommandUseCase {

    private final Map<String, BillingCommandStrategy> strategies;

    public BillingCommandHandler(Collection<BillingCommandStrategy> strategies) {
        this.strategies = strategies.stream()
            .collect(Collectors.toUnmodifiableMap(BillingCommandStrategy::type, Function.identity()));
    }

    @Override
    public void handle(InboundMessage command) {
        if (command.kind() != MessageKind.COMMAND) {
            throw new InvalidMessageException("O Billing só consome comandos; recebeu " + command.kind());
        }
        BillingCommandStrategy strategy = strategies.get(command.type());
        if (strategy == null) {
            throw new InvalidMessageException("Comando não suportado pelo Billing: " + command.type());
        }
        strategy.handle(command);
    }
}
