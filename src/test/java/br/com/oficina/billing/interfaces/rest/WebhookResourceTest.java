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
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Webhook do Mercado Pago (spec 05 §5 e §7): assinatura, corpo adulterado, duplicado, desconhecido, tamanho. */
@QuarkusTest
@QuarkusTestResource(LocalStackResource.class)
class WebhookResourceTest {

    private static final long TS = 1_704_908_010L;

    @Inject BillingScenario scenario;
    @Inject SimulatedPaymentGateway gateway;

    @BeforeEach
    void resetProvider() {
        gateway.reset();
    }

    @Test
    void signedNotification_shouldApproveThePaymentOnce() {
        Order order = scenario.issued();
        String providerId = scenario.providerId(scenario.pendingPix(order));
        gateway.simulate(providerId, ProviderStatus.APPROVED, "accredited");

        notify(providerId, "req-1", body(providerId)).then().statusCode(200).body("result", equalTo("UPDATED"));
        notify(providerId, "req-2", body(providerId)).then().statusCode(200).body("result", equalTo("UNCHANGED"));

        given().auth().oauth2(TestTokens.staff("ADMIN")).when().get("/admin/budgets/{n}", order.orderNumber())
            .then().body("payments[0].status", equalTo("APPROVED"));
    }

    @Test
    void invalidSignature_shouldBe401AndCounted() {
        given().contentType(ContentType.JSON).header("x-signature", "ts=1,v1=deadbeef").header("x-request-id", "r")
            .queryParam("data.id", "123").queryParam("type", "payment").body(body("123"))
            .when().post("/webhooks/mercadopago").then().statusCode(401);
        given().contentType(ContentType.JSON).body(body("123"))
            .when().post("/webhooks/mercadopago").then().statusCode(401);

        given().when().get("/q/metrics").then()
            .body(containsString("billing_webhook_invalid_signature_total"));
    }

    @Test
    void tamperedBody_shouldNotBeTrusted_theProviderAnswerWins() {
        Order order = scenario.issued();
        String providerId = scenario.providerId(scenario.pendingPix(order));
        String forged = """
            {"action": "payment.updated", "type": "payment", "data": {"id": "%s", "status": "approved"}}
            """.formatted(providerId);

        notify(providerId, "req-3", forged).then().statusCode(200).body("result", equalTo("UNCHANGED"));

        given().auth().oauth2(TestTokens.staff("ADMIN")).when().get("/admin/budgets/{n}", order.orderNumber())
            .then().body("payments[0].status", equalTo("PENDING"));
    }

    @Test
    void signatureForAnotherPayment_shouldBeRejected() {
        String signedForOther = WebhookSigner.header("111", "req-4", TS);

        given().contentType(ContentType.JSON).header("x-signature", signedForOther).header("x-request-id", "req-4")
            .queryParam("data.id", "222").queryParam("type", "payment").body(body("222"))
            .when().post("/webhooks/mercadopago").then().statusCode(401);
    }

    @Test
    void unknownPayment_shouldBeAcknowledgedAndIgnored() {
        notify("987654321", "req-5", body("987654321")).then().statusCode(200).body("result", equalTo("UNKNOWN"));
    }

    @Test
    void otherNotificationTypes_shouldBeIgnored() {
        given().contentType(ContentType.JSON).header("x-signature", WebhookSigner.header("5", "req-6", TS))
            .header("x-request-id", "req-6").queryParam("data.id", "5").queryParam("type", "merchant_order")
            .body("{}").when().post("/webhooks/mercadopago").then().statusCode(200).body("result", equalTo("IGNORED"));
    }

    @Test
    void dataIdFromTheBody_shouldBeUsedWhenTheQueryHasNone() {
        Order order = scenario.issued();
        String providerId = scenario.providerId(scenario.pendingPix(order));
        gateway.simulate(providerId, ProviderStatus.REJECTED, "cc_rejected_other_reason");

        given().contentType(ContentType.JSON).header("x-signature", WebhookSigner.header(providerId, "req-7", TS))
            .header("x-request-id", "req-7").body(body(providerId))
            .when().post("/webhooks/mercadopago").then().statusCode(200).body("result", equalTo("UPDATED"));
    }

    @Test
    void oversizedBody_shouldBeRefused() {
        given().contentType(ContentType.JSON).body("{\"pad\": \"" + "x".repeat(20_000) + "\"}")
            .when().post("/webhooks/mercadopago").then().statusCode(413);
    }

    private static io.restassured.response.Response notify(String dataId, String requestId, String body) {
        return given().contentType(ContentType.JSON)
            .header("x-signature", WebhookSigner.header(dataId, requestId, TS))
            .header("x-request-id", requestId)
            .queryParam("data.id", dataId).queryParam("type", "payment")
            .body(body)
            .when().post("/webhooks/mercadopago");
    }

    private static String body(String dataId) {
        return """
            {"action": "payment.updated", "api_version": "v1", "type": "payment", "data": {"id": "%s"}}
            """.formatted(dataId);
    }
}
