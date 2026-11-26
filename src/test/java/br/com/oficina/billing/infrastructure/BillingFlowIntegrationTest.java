package br.com.oficina.billing.infrastructure;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.oficina.billing.application.messaging.OutboundMessage;
import br.com.oficina.billing.domain.model.ProviderStatus;
import br.com.oficina.billing.infrastructure.configuration.BillingConfig;
import br.com.oficina.billing.infrastructure.messaging.CommandConsumer;
import br.com.oficina.billing.infrastructure.messaging.MessageEnvelopeCodec;
import br.com.oficina.billing.infrastructure.messaging.OutboxPublisher;
import br.com.oficina.billing.infrastructure.payment.SimulatedPaymentGateway;
import br.com.oficina.billing.infrastructure.payment.WebhookSigner;
import br.com.oficina.billing.testsupport.BillingScenario;
import br.com.oficina.billing.testsupport.BillingScenario.Order;
import br.com.oficina.billing.testsupport.Commands;
import br.com.oficina.billing.testsupport.LocalStackResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * O ciclo do Billing pelo caminho de produção: comando na fila FIFO (LocalStack) → consumidor → PostgreSQL + outbox →
 * relay → resposta na fila do OS, com {@code correlationId} e {@code causationId} preservados. Cobre a spec 05 §7:
 * orçamento, decisão pelo link, Pix, webhook, estorno e falha de estorno.
 */
@QuarkusTest
@QuarkusTestResource(LocalStackResource.class)
class BillingFlowIntegrationTest {


    @Inject SqsClient sqs;
    @Inject BillingConfig config;
    @Inject CommandConsumer consumer;
    @Inject OutboxPublisher publisher;
    @Inject MessageEnvelopeCodec codec;
    @Inject ObjectMapper mapper;
    @Inject BillingScenario scenario;
    @Inject SimulatedPaymentGateway gateway;
    @Inject EntityManager em;

    @BeforeEach
    void drainReplies() {
        gateway.reset();
        publisher.publishBatch(500);
        receiveReplies();
    }

    @Test
    void fullCycle_throughTheQueues() {
        Order order = scenario.newOrder();
        UUID generate = send(Commands.command("GenerateBudget", order.sagaId(), order.workOrderId(),
            Commands.generateBudgetPayload("450.00")), "req-flow-1");

        JsonNode generated = single("BudgetGenerated");
        assertThat(generated.get("correlationId").asText()).isEqualTo("req-flow-1");
        assertThat(generated.get("causationId").asText()).isEqualTo(generate.toString());
        assertThat(generated.get("sagaId").asText()).isEqualTo(order.sagaId().toString());
        String budgetId = generated.at("/payload/budgetId").asText();

        given().contentType(ContentType.JSON).body(Map.of("token", scenario.emailedToken(order)))
            .when().post("/public/budgets/{n}/approve", order.orderNumber()).then().statusCode(200);
        assertThat(single("BudgetApproved").at("/payload/by").asText()).isEqualTo("CLIENT_TOKEN");

        send(Commands.command("RequestPayment", order.sagaId(), order.workOrderId(),
            Map.of("budgetId", budgetId, "amount", "450.00", "attempt", 1)), "req-flow-2");
        String paymentId = single("PaymentRequested").at("/payload/paymentId").asText();

        String providerId = scenario.providerId(paymentId);
        gateway.simulate(providerId, ProviderStatus.APPROVED, "accredited");
        String event = WebhookSigner.paymentIntentEvent("payment_intent.succeeded", providerId);
        given().contentType(ContentType.JSON).header("Stripe-Signature", WebhookSigner.header(event)).body(event)
            .when().post("/webhooks/stripe").then().statusCode(200);
        JsonNode approved = single("PaymentApproved");
        assertThat(approved.at("/payload/amount").asText()).isEqualTo("450.00");
        assertThat(approved.get("causationId").isNull()).isTrue();

        send(Commands.command("RefundPayment", order.sagaId(), order.workOrderId(),
            Map.of("paymentId", paymentId, "reason", "execução falhou")), "req-flow-3");
        assertThat(single("PaymentRefunded").at("/payload/paymentId").asText()).isEqualTo(paymentId);
        assertThat(gateway.isRefunded(providerId)).isTrue();

        send(Commands.command("VoidBudget", order.sagaId(), order.workOrderId(),
            Map.of("budgetId", budgetId, "reason", "execução falhou")), "req-flow-4");
        assertThat(single("BudgetVoided").at("/payload/budgetId").asText()).isEqualTo(budgetId);

        assertThat(providerNotices(paymentId)).containsExactly("WEBHOOK:APPROVED");
    }

    @Test
    void duplicatedDelivery_shouldBeAnsweredOnce() {
        Order order = scenario.newOrder();
        OutboundMessage ignored = null;
        var command = Commands.command("GenerateBudget", order.sagaId(), order.workOrderId(),
            Commands.generateBudgetPayload("450.00"));
        String body = encode(command, "req-dup");

        sqs.sendMessage(r -> r.queueUrl(commandsQueue()).messageGroupId("wo-" + order.workOrderId())
            .messageDeduplicationId(UUID.randomUUID().toString()).messageBody(body));
        sqs.sendMessage(r -> r.queueUrl(commandsQueue()).messageGroupId("wo-" + order.workOrderId())
            .messageDeduplicationId(UUID.randomUUID().toString()).messageBody(body));
        consumer.pollOnce();
        consumer.pollOnce();
        publisher.publishBatch(50);

        assertThat(receiveReplies()).extracting(reply -> reply.get("type").asText()).containsExactly("BudgetGenerated");
        assertThat(ignored).isNull();
    }

    @Test
    void refusedRefund_shouldBeReportedToTheSaga() {
        Order order = scenario.issued();
        String paymentId = scenario.paidPix(order);
        publisher.publishBatch(50);
        receiveReplies();
        gateway.refuseRefunds("saldo insuficiente na conta");

        send(Commands.command("RefundPayment", order.sagaId(), order.workOrderId(),
            Map.of("paymentId", paymentId, "reason", "cancelamento")), "req-refund");

        assertThat(single("PaymentRefundFailed").at("/payload/detail").asText()).isEqualTo("saldo insuficiente na conta");
    }

    @Test
    void malformedCommand_shouldStayInTheQueue() {
        sqs.sendMessage(r -> r.queueUrl(commandsQueue()).messageGroupId("wo-bad")
            .messageDeduplicationId(UUID.randomUUID().toString()).messageBody("{\"não\": \"é um envelope\"}"));

        assertThat(consumer.pollOnce()).isZero();
    }

    private UUID send(br.com.oficina.billing.application.messaging.InboundMessage command, String correlationId) {
        sqs.sendMessage(r -> r.queueUrl(commandsQueue()).messageGroupId("wo-" + command.workOrderId())
            .messageDeduplicationId(command.messageId().toString()).messageBody(encode(command, correlationId)));
        assertThat(consumer.pollOnce()).isEqualTo(1);
        return command.messageId();
    }

    /** O OS publica comandos no mesmo envelope; aqui ele é montado com o codec deste serviço. */
    private String encode(br.com.oficina.billing.application.messaging.InboundMessage command, String correlationId) {
        Map<String, Object> payload = mapper.convertValue(raw(command), Map.class);
        OutboundMessage asSent = new OutboundMessage(command.messageId(), command.type(), command.kind(),
            command.sagaId(), command.workOrderId(), command.orderNumber(), null, OffsetDateTime.now(), payload);
        return codec.encode(asSent, correlationId, null);
    }

    private static Map<String, Object> raw(br.com.oficina.billing.application.messaging.InboundMessage command) {
        return switch (command.type()) {
            case "GenerateBudget" -> Commands.generateBudgetPayload("450.00");
            case "RequestPayment" -> Map.of("budgetId", command.payload().text("budgetId"),
                "amount", command.payload().text("amount"), "attempt", command.payload().integer("attempt"));
            case "VoidBudget" -> Map.of("budgetId", command.payload().text("budgetId"),
                "reason", command.payload().text("reason"));
            default -> Map.of("paymentId", command.payload().text("paymentId"), "reason", command.payload().text("reason"));
        };
    }

    private JsonNode single(String type) {
        publisher.publishBatch(50);
        List<JsonNode> replies = receiveReplies();
        assertThat(replies).extracting(reply -> reply.get("type").asText()).containsExactly(type);
        return replies.get(0);
    }

    private List<JsonNode> receiveReplies() {
        List<JsonNode> replies = new ArrayList<>();
        List<Message> batch;
        do {
            batch = sqs.receiveMessage(r -> r.queueUrl(repliesQueue()).maxNumberOfMessages(10).waitTimeSeconds(1))
                .messages();
            for (Message message : batch) {
                replies.add(read(message.body()));
                sqs.deleteMessage(r -> r.queueUrl(repliesQueue()).receiptHandle(message.receiptHandle()));
            }
        } while (!batch.isEmpty());
        return replies;
    }

    @SuppressWarnings("unchecked")
    private List<String> providerNotices(String paymentId) {
        return QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(
                "SELECT source || ':' || provider_status FROM payment_event WHERE payment_id = ?1 ORDER BY id")
            .setParameter(1, UUID.fromString(paymentId)).getResultList());
    }

    private JsonNode read(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String commandsQueue() {
        return config.messaging().queues().commands().orElseThrow();
    }

    private String repliesQueue() {
        return config.messaging().queues().osReplies().orElseThrow();
    }
}
