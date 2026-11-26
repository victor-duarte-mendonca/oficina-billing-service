package br.com.oficina.billing.interfaces.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import br.com.oficina.billing.domain.model.ProviderStatus;
import br.com.oficina.billing.infrastructure.payment.SimulatedPaymentGateway;
import br.com.oficina.billing.infrastructure.payment.WebhookSigner;
import br.com.oficina.billing.testsupport.BillingScenario;
import br.com.oficina.billing.testsupport.BillingScenario.Order;
import br.com.oficina.billing.testsupport.LocalStackResource;
import br.com.oficina.billing.testsupport.TestTokens;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Webhook da Stripe (spec 05 §5 e §7): assinatura, relógio, corpo adulterado, evento duplicado, desconhecido, tipos
 * ignorados e tamanho.
 */
@QuarkusTest
@QuarkusTestResource(LocalStackResource.class)
class WebhookResourceTest {

    private static final String SUCCEEDED = "payment_intent.succeeded";

    @Inject BillingScenario scenario;
    @Inject SimulatedPaymentGateway gateway;

    @BeforeEach
    void resetProvider() {
        gateway.reset();
    }

    @Test
    void signedEvent_shouldApproveThePaymentOnce_andTheReplayIsHarmless() {
        Order order = scenario.issued();
        String providerId = scenario.providerId(scenario.pendingPix(order));
        gateway.simulate(providerId, ProviderStatus.APPROVED, "succeeded");
        String event = WebhookSigner.paymentIntentEvent(SUCCEEDED, providerId);

        send(event).then().statusCode(200).body("result", equalTo("UPDATED"));
        send(event).then().statusCode(200).body("result", equalTo("UNCHANGED"));

        given().auth().oauth2(TestTokens.staff("ADMIN")).when().get("/admin/budgets/{n}", order.orderNumber())
            .then().body("payments[0].status", equalTo("APPROVED"));
    }

    @Test
    void invalidSignature_shouldBe401AndCounted() {
        String event = WebhookSigner.paymentIntentEvent(SUCCEEDED, "pi_123");

        given().contentType(ContentType.JSON).header("Stripe-Signature", "t=1,v1=deadbeef").body(event)
            .when().post("/webhooks/stripe").then().statusCode(401);
        given().contentType(ContentType.JSON).body(event)
            .when().post("/webhooks/stripe").then().statusCode(401);

        given().when().get("/q/metrics").then()
            .body(containsString("billing_webhook_invalid_signature_total"));
    }

    @Test
    void staleSignature_shouldBeRejectedAsAReplay() {
        String event = WebhookSigner.paymentIntentEvent(SUCCEEDED, "pi_123");
        String stale = WebhookSigner.header(event, Instant.now().getEpochSecond() - 3600);

        given().contentType(ContentType.JSON).header("Stripe-Signature", stale).body(event)
            .when().post("/webhooks/stripe").then().statusCode(401);
    }

    @Test
    void bodyChangedAfterSigning_shouldBeRejected() {
        String signed = WebhookSigner.paymentIntentEvent(SUCCEEDED, "pi_111");
        String forged = WebhookSigner.paymentIntentEvent(SUCCEEDED, "pi_222");

        given().contentType(ContentType.JSON).header("Stripe-Signature", WebhookSigner.header(signed)).body(forged)
            .when().post("/webhooks/stripe").then().statusCode(401);
    }

    @Test
    void signedEventClaimingSuccess_shouldNotBeTrusted_theProviderAnswerWins() {
        Order order = scenario.issued();
        String providerId = scenario.providerId(scenario.pendingPix(order));
        String event = WebhookSigner.event(SUCCEEDED,
            "{\"id\":\"" + providerId + "\",\"object\":\"payment_intent\",\"status\":\"succeeded\"}");

        send(event).then().statusCode(200).body("result", equalTo("UNCHANGED"));

        given().auth().oauth2(TestTokens.staff("ADMIN")).when().get("/admin/budgets/{n}", order.orderNumber())
            .then().body("payments[0].status", equalTo("PENDING"));
    }

    @Test
    void unknownPayment_shouldBeAcknowledgedAndIgnored() {
        send(WebhookSigner.paymentIntentEvent(SUCCEEDED, "pi_desconhecido")).then().statusCode(200)
            .body("result", equalTo("UNKNOWN"));
    }

    @Test
    void failureEvent_shouldReconsultAndApplyTheProviderStatus() {
        Order order = scenario.issued();
        String providerId = scenario.providerId(scenario.pendingPix(order));
        gateway.simulate(providerId, ProviderStatus.EXPIRED, "payment_intent_payment_attempt_failed");

        send(WebhookSigner.paymentIntentEvent("payment_intent.payment_failed", providerId)).then().statusCode(200)
            .body("result", equalTo("UPDATED"));
    }

    @Test
    void chargeEvents_shouldPointToThePaymentIntent() {
        Order order = scenario.issued();
        String providerId = scenario.providerId(scenario.pendingPix(order));
        gateway.simulate(providerId, ProviderStatus.APPROVED, "succeeded");
        String event = WebhookSigner.event("charge.refunded",
            "{\"id\":\"ch_1\",\"object\":\"charge\",\"payment_intent\":\"" + providerId + "\"}");

        send(event).then().statusCode(200).body("result", equalTo("UPDATED"));
    }

    @Test
    void otherEventTypes_andDisputes_shouldBeIgnored() {
        send(WebhookSigner.event("customer.created", "{\"id\":\"cus_1\"}")).then().statusCode(200)
            .body("result", equalTo("IGNORED"));
        send(WebhookSigner.event("charge.dispute.created", "{\"id\":\"dp_1\",\"payment_intent\":\"pi_1\"}"))
            .then().statusCode(200).body("result", equalTo("IGNORED"));
    }

    @Test
    void signedButNotAnEvent_shouldBeABadRequest() {
        send("[]").then().statusCode(400);
        send("não é json").then().statusCode(400);
    }

    @Test
    void oversizedBody_shouldBeRefused() {
        given().contentType(ContentType.JSON).body("{\"pad\": \"" + "x".repeat(70_000) + "\"}")
            .when().post("/webhooks/stripe").then().statusCode(413);
    }

    private static Response send(String rawBody) {
        return given().contentType(ContentType.JSON).header("Stripe-Signature", WebhookSigner.header(rawBody))
            .body(rawBody).when().post("/webhooks/stripe");
    }
}
