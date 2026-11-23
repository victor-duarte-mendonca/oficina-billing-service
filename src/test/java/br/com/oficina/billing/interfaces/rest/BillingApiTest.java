package br.com.oficina.billing.interfaces.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import br.com.oficina.billing.infrastructure.payment.SimulatedPaymentGateway;
import br.com.oficina.billing.testsupport.BillingScenario;
import br.com.oficina.billing.testsupport.BillingScenario.Order;
import br.com.oficina.billing.testsupport.LocalStackResource;
import br.com.oficina.billing.testsupport.TestTokens;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * API do Billing com JWT real, PostgreSQL real e o provedor simulado: os três canais de decisão, o Pix do cliente,
 * os papéis (401/403), a não revelação de OS alheia e a rota de simulação.
 */
@QuarkusTest
@QuarkusTestResource(LocalStackResource.class)
class BillingApiTest {

    @Inject BillingScenario scenario;
    @Inject SimulatedPaymentGateway gateway;

    @BeforeEach
    void resetProvider() {
        gateway.reset();
    }

    @Test
    void emailedToken_shouldApproveOnceAndRepeatIdempotently() {
        Order order = scenario.issued();
        String token = scenario.emailedToken(order);

        publicDecision(order, "approve", token).then().statusCode(200)
            .body("status", equalTo("APPROVED")).body("decidedBy", equalTo("CLIENT_TOKEN"))
            .body("total", equalTo("450.00")).body("items", hasSize(2));
        publicDecision(order, "approve", token).then().statusCode(200).body("status", equalTo("APPROVED"));
        publicDecision(order, "reject", token).then().statusCode(422);
    }

    @Test
    void wrongToken_shouldLookExactlyLikeAMissingOrder() {
        Order order = scenario.issued();

        String wrongToken = publicDecision(order, "approve", "token-errado").then().statusCode(404).extract().path("message");
        String missingOrder = publicDecision(scenario.newOrder(), "approve", "token-errado").then().statusCode(404)
            .extract().path("message");

        org.assertj.core.api.Assertions.assertThat(wrongToken).isEqualTo(missingOrder);
        publicDecision(order, "approve", "").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"token\":\"x\"}")
            .when().post("/public/budgets/{n}/approve", "OS-1").then().statusCode(400);
    }

    @Test
    void client_shouldSeeAndDecideOnlyTheirOwnBudget() {
        Order order = scenario.issued();

        asClient(7).when().get("/me/budgets/{n}", order.orderNumber()).then().statusCode(404);
        asClient(42).when().get("/me/budgets/{n}", order.orderNumber()).then().statusCode(200)
            .body("status", equalTo("SENT")).body("items[0].kind", equalTo("PART"));
        asClient(42).when().get("/me/budgets/{n}/payment", order.orderNumber()).then().statusCode(404);
        asClient(42).body("{\"decision\": \"APPROVE\"}").when().post("/me/budgets/{n}/approval", order.orderNumber())
            .then().statusCode(200).body("decidedBy", equalTo("CLIENT_JWT"));
        asClient(42).body("{}").when().post("/me/budgets/{n}/approval", order.orderNumber()).then().statusCode(400);
    }

    @Test
    void clientPayment_shouldShowTheCurrentPix() {
        Order order = scenario.issued();
        scenario.pendingPix(order);

        asClient(42).when().get("/me/budgets/{n}/payment", order.orderNumber()).then().statusCode(200)
            .body("status", equalTo("PENDING")).body("amount", equalTo("450.00"))
            .body("qrCode", notNullValue()).body("qrCodeBase64", notNullValue()).body("expiresAt", notNullValue());
    }

    @Test
    void staff_shouldSeeBudgetAndAttemptsAndRecordTheDecision() {
        Order order = scenario.issued();

        asStaff("MECHANIC").when().get("/admin/budgets/{n}", order.orderNumber()).then().statusCode(200)
            .body("budget.status", equalTo("SENT")).body("payments", hasSize(0));
        asStaff("MECHANIC").when().post("/admin/budgets/{n}/approve", order.orderNumber()).then().statusCode(403);
        asStaff("ADMIN").when().post("/admin/budgets/{n}/reject", order.orderNumber()).then().statusCode(200)
            .body("status", equalTo("REJECTED")).body("decidedBy", equalTo("STAFF"));
        asStaff("ADMIN").when().post("/admin/budgets/{n}/approve", order.orderNumber()).then().statusCode(422)
            .body("message", containsString("recusado")).body("correlationId", notNullValue());
    }

    @Test
    void roles_shouldBeEnforced() {
        given().when().get("/admin/budgets/{n}", "OS-000001").then().statusCode(401);
        asClient(42).when().get("/admin/budgets/{n}", "OS-000001").then().statusCode(403);
        asStaff("ADMIN").when().get("/me/budgets/{n}", "OS-000001").then().statusCode(403);
        given().auth().oauth2(TestTokens.clientWithoutIdentity()).when().get("/me/budgets/{n}", "OS-000001")
            .then().statusCode(403);
    }

    @Test
    void simulation_shouldPlayTheBankAndUpdateThePayment() {
        Order order = scenario.issued();
        String paymentId = scenario.pendingPix(order);

        asStaff("MECHANIC").body("{\"status\": \"APPROVED\"}")
            .when().post("/admin/payments/{id}/simulate", paymentId).then().statusCode(403);
        asStaff("ADMIN").body("{\"status\": \"APPROVED\", \"detail\": \"accredited\"}")
            .when().post("/admin/payments/{id}/simulate", paymentId).then().statusCode(200)
            .body("result", equalTo("UPDATED"));
        asStaff("ADMIN").when().get("/admin/budgets/{n}", order.orderNumber()).then()
            .body("payments[0].status", equalTo("APPROVED")).body("payments[0].detail", nullValue());
        asStaff("ADMIN").body("{\"status\": \"APPROVED\"}")
            .when().post("/admin/payments/{id}/simulate", UUID.randomUUID()).then().statusCode(404);
    }

    @Test
    void providerDown_shouldAnswer503WithoutDetails() {
        Order order = scenario.issued();
        String paymentId = scenario.pendingPix(order);
        gateway.setUnavailable(true);

        asStaff("ADMIN").body("{\"status\": \"APPROVED\"}")
            .when().post("/admin/payments/{id}/simulate", paymentId).then().statusCode(503)
            .body("message", containsString("indisponível"));
    }

    @Test
    void correlationId_shouldBeEchoed() {
        asStaff("ADMIN").header("X-Correlation-Id", "req-billing-1").when().get("/admin/budgets/{n}", "OS-999999")
            .then().statusCode(404).header("X-Correlation-Id", equalTo("req-billing-1"))
            .body("correlationId", equalTo("req-billing-1"));
    }

    @Test
    void probesAndMetrics_shouldBeAvailable() {
        scenario.issued();

        given().when().get("/q/health/ready").then().statusCode(200).body("status", equalTo("UP"));
        given().when().get("/q/metrics").then().statusCode(200)
            .body(containsString("billing_events_total{type=\"BudgetGenerated\"}"));
        given().when().get("/q/openapi").then().statusCode(200).body(containsString("/webhooks/mercadopago"));
    }

    @Test
    void openApi_shouldListTheBillingRoutes() {
        given().accept(ContentType.JSON).when().get("/q/openapi").then().statusCode(200)
            .body("paths.keySet()", hasItem("/me/budgets/{orderNumber}/payment"));
    }

    private static io.restassured.response.Response publicDecision(Order order, String decision, String token) {
        return given().contentType(ContentType.JSON).body("{\"token\": \"" + token + "\"}")
            .when().post("/public/budgets/{n}/" + decision, order.orderNumber());
    }

    private static RequestSpecification asClient(long clientId) {
        return given().auth().oauth2(TestTokens.client(clientId)).contentType(ContentType.JSON);
    }

    private static RequestSpecification asStaff(String role) {
        return given().auth().oauth2(TestTokens.staff(role)).contentType(ContentType.JSON);
    }
}
