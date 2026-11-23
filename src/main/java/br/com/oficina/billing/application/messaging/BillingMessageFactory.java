package br.com.oficina.billing.application.messaging;

import br.com.oficina.billing.domain.event.BillingEvent;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Converte os eventos do domínio nas respostas do contrato ({@code contracts/billing-events.schema.json}). Único
 * lugar que conhece os nomes dos campos de payload do Billing.
 */
public final class BillingMessageFactory {

    private static final int MAX_REASON = 300;

    /**
     * @param causationId comando que provocou o evento; {@code null} quando a origem foi a API, o webhook ou um job
     */
    public OutboundMessage from(BillingEvent event, UUID causationId, OffsetDateTime now) {
        return new OutboundMessage(UUID.randomUUID(), event.type(), MessageKind.EVENT, event.ref().sagaId(),
            event.ref().workOrderId(), event.ref().orderNumber(), causationId, now, payload(event));
    }

    private static Map<String, Object> payload(BillingEvent event) {
        return switch (event) {
            case BillingEvent.BudgetGenerated e ->
                fields("budgetId", e.budgetId(), "total", e.total().toWire(), "expiresAt", instant(e.expiresAt()));
            case BillingEvent.BudgetGenerationFailed e -> fields("reason", reason(e.reason()));
            case BillingEvent.BudgetApproved e -> fields("budgetId", e.budgetId(), "by", e.by().name());
            case BillingEvent.BudgetRejected e -> fields("budgetId", e.budgetId(), "by", e.by().name());
            case BillingEvent.BudgetExpired e -> fields("budgetId", e.budgetId());
            case BillingEvent.BudgetVoided e -> fields("budgetId", e.budgetId());
            case BillingEvent.PaymentRequested e ->
                fields("paymentId", e.paymentId(), "expiresAt", instant(e.expiresAt()));
            case BillingEvent.PaymentRequestFailed e -> fields("reason", reason(e.reason()));
            case BillingEvent.PaymentApproved e -> fields("paymentId", e.paymentId(), "amount", e.amount().toWire());
            case BillingEvent.PaymentRejected e -> fields("paymentId", e.paymentId(), "detail", reason(e.detail()));
            case BillingEvent.PaymentExpired e -> fields("paymentId", e.paymentId());
            case BillingEvent.PaymentRefunded e -> fields("paymentId", e.paymentId());
            case BillingEvent.PaymentRefundFailed e -> fields("paymentId", e.paymentId(), "detail", reason(e.detail()));
        };
    }

    private static String instant(Instant value) {
        return value.toString();
    }

    /** O contrato limita motivos a 300 caracteres e não aceita vazio (mensagens de erro do provedor variam). */
    static String reason(String text) {
        if (text == null || text.isBlank()) {
            return "não informado";
        }
        return text.length() <= MAX_REASON ? text : text.substring(0, MAX_REASON);
    }

    /** Identificadores viram texto (o contrato tipa {@code budgetId}/{@code paymentId} como string). */
    private static Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            map.put((String) keyValues[i], value instanceof UUID id ? id.toString() : value);
        }
        return map;
    }
}
