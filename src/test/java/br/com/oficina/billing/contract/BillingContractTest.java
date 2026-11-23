package br.com.oficina.billing.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import br.com.oficina.billing.application.messaging.BillingMessageFactory;
import br.com.oficina.billing.application.messaging.InboundMessage;
import br.com.oficina.billing.application.messaging.OutboundMessage;
import br.com.oficina.billing.application.service.BillingCommandHandler;
import br.com.oficina.billing.application.service.EventPublisher;
import br.com.oficina.billing.application.service.command.GenerateBudgetStrategy;
import br.com.oficina.billing.application.service.command.RefundPaymentStrategy;
import br.com.oficina.billing.application.service.command.RequestPaymentStrategy;
import br.com.oficina.billing.application.service.command.VoidBudgetStrategy;
import br.com.oficina.billing.domain.event.BillingEvent;
import br.com.oficina.billing.domain.model.DecisionChannel;
import br.com.oficina.billing.domain.valueobject.Money;
import br.com.oficina.billing.domain.valueobject.SagaRef;
import br.com.oficina.billing.infrastructure.messaging.MessageEnvelopeCodec;
import br.com.oficina.billing.infrastructure.payment.SimulatedPaymentGateway;
import br.com.oficina.billing.testsupport.InMemoryBillingStore;
import br.com.oficina.billing.testsupport.TestDoubles.MutableClock;
import br.com.oficina.billing.testsupport.TestDoubles.RecordingMetrics;
import br.com.oficina.billing.testsupport.TestDoubles.RecordingNotifier;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Contrato dos dois lados. Produtor: cada evento que o Billing publica, pelo codec real, é válido em
 * {@code billing-events.schema.json} (o dono do schema é este serviço). Consumidor: os comandos de exemplo do OS são
 * válidos no schema do OS e são tratados por este serviço sem erro de contrato.
 */
class BillingContractTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-23T14:00:00Z");
    private static final SagaRef REF = new SagaRef(UUID.fromString("0b7d2f5e-1111-4000-8000-000000000001"), 1, "OS-000001");

    private final MessageEnvelopeCodec codec = new MessageEnvelopeCodec(ContractSchemas.MAPPER);
    private final BillingMessageFactory factory = new BillingMessageFactory();

    static Stream<BillingEvent> events() {
        UUID budget = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        Instant expires = NOW.toInstant().plusSeconds(1800);
        return Stream.of(
            new BillingEvent.BudgetGenerated(REF, budget, Money.of("450.00"), expires),
            new BillingEvent.BudgetGenerationFailed(REF, "x".repeat(400)),
            new BillingEvent.BudgetApproved(REF, budget, DecisionChannel.CLIENT_TOKEN),
            new BillingEvent.BudgetRejected(REF, budget, DecisionChannel.STAFF),
            new BillingEvent.BudgetExpired(REF, budget),
            new BillingEvent.BudgetVoided(REF, budget),
            new BillingEvent.PaymentRequested(REF, payment, expires),
            new BillingEvent.PaymentRequestFailed(REF, ""),
            new BillingEvent.PaymentApproved(REF, payment, Money.of("450")),
            new BillingEvent.PaymentRejected(REF, payment, "cc_rejected_insufficient_amount"),
            new BillingEvent.PaymentExpired(REF, payment),
            new BillingEvent.PaymentRefunded(REF, payment),
            new BillingEvent.PaymentRefundFailed(REF, payment, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("events")
    void publishedEvent_shouldMatchTheContract(BillingEvent event) {
        JsonNode wire = wire(factory.from(event, UUID.randomUUID(), NOW));

        assertThat(ContractSchemas.validate("billing-events", wire)).isEmpty();
        assertThat(wire.get("type").asText()).isEqualTo(event.type());
    }

    @Test
    void everyEventOfTheCatalog_shouldBeProducedByThisService() {
        JsonNode typeEnum = ContractSchemas.read("billing-events.schema.json").at("/allOf/1/properties/type/enum");
        Set<String> catalog = new HashSet<>();
        typeEnum.forEach(node -> catalog.add(node.asText()));
        Set<String> produced = new HashSet<>(events().map(BillingEvent::type).toList());

        assertThat(produced).isEqualTo(catalog);
        assertThat(BillingEvent.class.getPermittedSubclasses()).hasSize(catalog.size());
    }

    @Test
    void examples_shouldMatchTheirSchemas() {
        ContractSchemas.read("examples/billing-events.json")
            .forEach(example -> assertThat(ContractSchemas.validate("billing-events", example)).isEmpty());
        ContractSchemas.read("examples/os-commands.json")
            .forEach(example -> assertThat(ContractSchemas.validate("os-commands", example)).isEmpty());
    }

    @Test
    void commandsFromTheOrderService_shouldBeUnderstood() {
        InMemoryBillingStore store = new InMemoryBillingStore();
        SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();
        MutableClock clock = new MutableClock(NOW.toInstant());
        RecordingMetrics metrics = new RecordingMetrics();
        EventPublisher events = new EventPublisher(store, factory, metrics, clock);
        BillingCommandHandler handler = new BillingCommandHandler(List.of(
            new GenerateBudgetStrategy(store, store, store.budgetRepository, events, new RecordingNotifier(), clock),
            new RequestPaymentStrategy(store, store, store.budgetRepository, store.paymentRepository, gateway, events,
                metrics, clock, Duration.ofMinutes(30)),
            new VoidBudgetStrategy(store, store, store.budgetRepository, events),
            new RefundPaymentStrategy(store, store, store.paymentRepository, gateway, events, metrics)));

        StreamSupport.stream(ContractSchemas.read("examples/os-commands.json").spliterator(), false)
            .map(example -> codec.decode(example.toString()))
            .forEach(command -> assertThatCode(() -> handler.handle(command)).doesNotThrowAnyException());

        assertThat(store.outboxTypes()).containsExactly("BudgetGenerated", "BudgetGenerated", "PaymentRequestFailed",
            "BudgetVoided", "PaymentRefundFailed");
        store.outbox.forEach(reply -> assertThat(ContractSchemas.validate("billing-events", wire(reply))).isEmpty());
    }

    @Test
    void decodedCommand_shouldKeepTheEnvelope() {
        JsonNode example = ContractSchemas.read("examples/os-commands.json").get(0);

        InboundMessage command = codec.decode(example.toString());

        assertThat(command.type()).isEqualTo("GenerateBudget");
        assertThat(command.correlationId()).isEqualTo("req-1");
        assertThat(command.orderNumber()).isEqualTo("OS-000001");
        assertThat(command.payload().object("customer").text("name")).isEqualTo("Maria Souza");
    }

    private JsonNode wire(OutboundMessage message) {
        try {
            return ContractSchemas.MAPPER.readTree(codec.encode(message, "req-contract",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
