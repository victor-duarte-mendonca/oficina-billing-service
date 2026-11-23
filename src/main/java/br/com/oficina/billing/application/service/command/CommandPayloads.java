package br.com.oficina.billing.application.service.command;

import br.com.oficina.billing.application.messaging.InboundMessage;
import br.com.oficina.billing.application.messaging.InvalidMessageException;
import br.com.oficina.billing.application.messaging.MessagePayload;
import br.com.oficina.billing.domain.model.Budget;
import br.com.oficina.billing.domain.model.BudgetLine;
import br.com.oficina.billing.domain.model.Customer;
import br.com.oficina.billing.domain.valueobject.SagaRef;
import java.time.Duration;
import java.util.List;

/** Leitura dos payloads dos comandos do OS ({@code contracts/os-commands.schema.json}). */
final class CommandPayloads {

    private CommandPayloads() {
    }

    static SagaRef ref(InboundMessage command) {
        return new SagaRef(command.requiredSagaId(), command.workOrderId(), command.orderNumber());
    }

    static Budget.Request budgetRequest(InboundMessage command) {
        MessagePayload payload = command.payload();
        MessagePayload customer = payload.object("customer");
        List<BudgetLine> lines = payload.objects("items").stream().map(CommandPayloads::line).toList();
        return new Budget.Request(ref(command), payload.longInteger("clientId"),
            new Customer(customer.text("name"), customer.optionalText("email").orElse(null)),
            lines, payload.money("total"), Duration.ofSeconds(payload.longInteger("approvalTtlSeconds")));
    }

    private static BudgetLine line(MessagePayload item) {
        return new BudgetLine(kind(item.text("kind")), item.longInteger("refId"), item.text("name"),
            item.integer("quantity"), item.money("unitPrice"));
    }

    private static BudgetLine.ItemKind kind(String value) {
        try {
            return BudgetLine.ItemKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("Tipo de item inválido: " + value);
        }
    }
}
