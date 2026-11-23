package br.com.oficina.billing.testsupport;

import br.com.oficina.billing.application.messaging.InboundMessage;
import br.com.oficina.billing.application.messaging.MessageKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Comandos do OS como chegam da fila, no formato de {@code contracts/os-commands.schema.json}. */
public final class Commands {

    private Commands() {
    }

    public static InboundMessage generateBudget(UUID sagaId, long workOrderId, String total) {
        return command("GenerateBudget", sagaId, workOrderId, generateBudgetPayload(total));
    }

    public static Map<String, Object> generateBudgetPayload(String total) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", "Maria Souza");
        customer.put("email", "maria@exemplo.com");
        return Map.of(
            "clientId", 42,
            "customer", customer,
            "items", List.of(
                Map.of("kind", "PART", "refId", 7, "name", "Pastilha de freio", "quantity", 2, "unitPrice", "100.00"),
                Map.of("kind", "SERVICE", "refId", 3, "name", "Troca de pastilhas", "quantity", 1, "unitPrice", "250.00")),
            "total", total,
            "approvalTtlSeconds", 259_200);
    }

    public static InboundMessage requestPayment(UUID sagaId, long workOrderId, String budgetId, String amount, int attempt) {
        return command("RequestPayment", sagaId, workOrderId,
            Map.of("budgetId", budgetId, "amount", amount, "attempt", attempt));
    }

    public static InboundMessage voidBudget(UUID sagaId, long workOrderId, String budgetId) {
        return command("VoidBudget", sagaId, workOrderId, Map.of("budgetId", budgetId, "reason", "cliente recusou"));
    }

    public static InboundMessage refundPayment(UUID sagaId, long workOrderId, String paymentId) {
        return command("RefundPayment", sagaId, workOrderId, Map.of("paymentId", paymentId, "reason", "execução falhou"));
    }

    public static InboundMessage command(String type, UUID sagaId, long workOrderId, Map<String, Object> payload) {
        return InboundMessage.of(UUID.randomUUID(), type, MessageKind.COMMAND, sagaId, workOrderId,
            orderNumber(workOrderId), payload);
    }

    public static String orderNumber(long workOrderId) {
        return "OS-%06d".formatted(workOrderId);
    }
}
