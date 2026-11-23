package br.com.oficina.billing.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

    private static final String SECRET = "segredo-de-teste";
    private final MercadoPagoSignature signature = new MercadoPagoSignature(SECRET);

    @Test
    void validSignature_shouldBeAccepted() {
        String v1 = signature.sign("id:123456;request-id:req-1;ts:1704908010;");

        assertThat(signature.isValid("ts=1704908010,v1=" + v1, "req-1", "123456")).isTrue();
        assertThat(signature.isValid(" ts=1704908010 , v1=" + v1.toUpperCase(), "req-1", "123456")).isTrue();
    }

    @Test
    void anyTamperedPart_shouldBeRejected() {
        String v1 = signature.sign("id:123456;request-id:req-1;ts:1704908010;");

        assertThat(signature.isValid("ts=1704908010,v1=" + v1, "req-1", "999999")).isFalse();
        assertThat(signature.isValid("ts=1704908011,v1=" + v1, "req-1", "123456")).isFalse();
        assertThat(signature.isValid("ts=1704908010,v1=" + v1, "req-2", "123456")).isFalse();
        assertThat(new MercadoPagoSignature("outro").isValid("ts=1704908010,v1=" + v1, "req-1", "123456")).isFalse();
    }

    @Test
    void malformedHeader_shouldBeRejected() {
        assertThat(signature.isValid(null, "req-1", "1")).isFalse();
        assertThat(signature.isValid("v1=abc", "req-1", "1")).isFalse();
        assertThat(signature.isValid("ts=1,v1=", "req-1", "1")).isFalse();
        assertThat(signature.isValid("lixo", "req-1", "1")).isFalse();
    }

    @Test
    void manifest_shouldOmitMissingPartsAndLowercaseAlphanumericIds() {
        assertThat(MercadoPagoSignature.manifest("ABC123", "req", "9")).isEqualTo("id:abc123;request-id:req;ts:9;");
        assertThat(MercadoPagoSignature.manifest(null, null, "9")).isEqualTo("ts:9;");
        assertThat(MercadoPagoSignature.manifest("a-b", "", "9")).isEqualTo("id:a-b;ts:9;");
    }

    @Test
    void authenticator_shouldFailClosedWithTheRealProvider() {
        String v1 = signature.sign("id:1;ts:5;");

        assertThat(new WebhookAuthenticator(Optional.empty(), true).accepts("ts=5,v1=" + v1, null, "1")).isFalse();
        assertThat(new WebhookAuthenticator(Optional.of(" "), true).accepts("ts=5,v1=" + v1, null, "1")).isFalse();
        assertThat(new WebhookAuthenticator(Optional.of(SECRET), true).accepts("ts=5,v1=" + v1, null, "1")).isTrue();
        assertThat(new WebhookAuthenticator(Optional.empty(), false).accepts(null, null, "1")).isTrue();
        assertThat(new WebhookAuthenticator(Optional.of(SECRET), false).accepts(null, null, "1")).isFalse();
    }
}
