package br.com.oficina.billing.application.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.oficina.billing.domain.valueobject.Money;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Payload fora do contrato nunca vira cobrança: tipo errado ou campo ausente é mensagem inválida (vai à DLQ). */
class MessagePayloadTest {

    @Test
    void typedReads_shouldWorkForValidFields() {
        UUID id = UUID.randomUUID();
        Map<String, Object> values = new HashMap<>();
        values.put("amount", "450.00");
        values.put("budgetId", id.toString());
        values.put("attempt", 2);
        values.put("email", null);
        values.put("customer", Map.of("name", "Ana"));
        values.put("items", List.of(Map.of("name", "Filtro")));
        MessagePayload payload = new MessagePayload(values);

        assertThat(payload.money("amount")).isEqualTo(Money.of("450.00"));
        assertThat(payload.uuid("budgetId")).isEqualTo(id);
        assertThat(payload.integer("attempt")).isEqualTo(2);
        assertThat(payload.optionalText("email")).isEmpty();
        assertThat(payload.object("customer").text("name")).isEqualTo("Ana");
        assertThat(payload.objects("items")).hasSize(1);
        assertThat(new MessagePayload(null).optionalText("x")).isEmpty();
    }

    @Test
    void invalidFields_shouldBeRejected() {
        MessagePayload payload = new MessagePayload(Map.of("amount", "-1", "budgetId", "não-é-uuid", "attempt", "dois",
            "text", " ", "customer", "Ana", "items", List.of("x")));

        assertThatThrownBy(() -> payload.money("amount")).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> payload.uuid("budgetId")).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> payload.integer("attempt")).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> payload.text("text")).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> payload.object("customer")).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> payload.objects("items")).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> payload.text("ausente")).isInstanceOf(InvalidMessageException.class)
            .hasMessageContaining("ausente");
    }

    @Test
    void commandWithoutSaga_shouldBeInvalid() {
        InboundMessage command = InboundMessage.of(UUID.randomUUID(), "VoidBudget", MessageKind.COMMAND, null, 1L,
            "OS-000001", Map.of());

        assertThatThrownBy(command::requiredSagaId).isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void reasons_shouldFitTheContract() {
        assertThat(BillingMessageFactory.reason(null)).isEqualTo("não informado");
        assertThat(BillingMessageFactory.reason("x".repeat(500))).hasSize(300);
        assertThat(BillingMessageFactory.reason("curto")).isEqualTo("curto");
    }
}
