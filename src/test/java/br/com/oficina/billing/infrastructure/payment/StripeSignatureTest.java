package br.com.oficina.billing.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Verificação de {@code Stripe-Signature} (spec 05 §5): HMAC sobre {@code t.corpo}, rotação, tolerância, fail closed. */
class StripeSignatureTest {

    private static final String SECRET = "whsec_exemplo";
    private static final long NOW = 1_795_700_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private static final String BODY = "{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\"}";

    private final StripeSignature signature = new StripeSignature(SECRET, Duration.ofMinutes(5), CLOCK);

    private static String header(long t, String body) {
        return "t=" + t + ",v1=" + StripeSignature.sign(SECRET, t, body);
    }

    @Test
    void validSignature_shouldBeAccepted() {
        assertThat(signature.isValid(header(NOW, BODY), BODY)).isTrue();
        assertThat(signature.isValid(" t=" + NOW + " , v1=" + StripeSignature.sign(SECRET, NOW, BODY).toUpperCase(), BODY))
            .isTrue();
    }

    @Test
    void anyTamperedPart_shouldBeRejected() {
        String valid = header(NOW, BODY);

        assertThat(signature.isValid(valid, BODY + " ")).isFalse();
        assertThat(signature.isValid(valid.replace("t=" + NOW, "t=" + (NOW + 1)), BODY)).isFalse();
        assertThat(new StripeSignature("outro", Duration.ofMinutes(5), CLOCK).isValid(valid, BODY)).isFalse();
    }

    @Test
    void rolledSecret_shouldAcceptAnyOfTheV1Signatures() {
        String header = "t=" + NOW + ",v1=" + "0".repeat(64) + ",v1=" + StripeSignature.sign(SECRET, NOW, BODY)
            + ",v0=" + "f".repeat(64);

        assertThat(signature.isValid(header, BODY)).isTrue();
    }

    @Test
    void onlyV0_orDebugEntries_shouldNotAuthenticate() {
        String v0 = "t=" + NOW + ",v0=" + StripeSignature.sign(SECRET, NOW, BODY);
        String debugOnly = "t=" + NOW + ",secret=whsec_****,destination=we_1,payload=a25aefc6";

        assertThat(signature.isValid(v0, BODY)).isFalse();
        assertThat(signature.isValid(debugOnly, BODY)).isFalse();
    }

    @Test
    void unknownEntries_shouldBeIgnored() {
        String header = header(NOW, BODY) + ",secret=whsec_****,destination=we_1,payload=a25aefc6";

        assertThat(signature.isValid(header, BODY)).isTrue();
    }

    @Test
    void timestampOutsideTheTolerance_shouldBeRejected() {
        assertThat(signature.isValid(header(NOW - 301, BODY), BODY)).isFalse();
        assertThat(signature.isValid(header(NOW + 301, BODY), BODY)).isFalse();
        assertThat(signature.isValid(header(NOW - 300, BODY), BODY)).isTrue();
    }

    @Test
    void zeroTolerance_shouldNotBeAllowed() {
        assertThatThrownBy(() -> new StripeSignature(SECRET, Duration.ZERO, CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedHeader_shouldBeRejected() {
        assertThat(signature.isValid(null, BODY)).isFalse();
        assertThat(signature.isValid(header(NOW, BODY), null)).isFalse();
        assertThat(signature.isValid("v1=abc", BODY)).isFalse();
        assertThat(signature.isValid("t=abc,v1=abc", BODY)).isFalse();
        assertThat(signature.isValid("t=" + NOW + ",v1=", BODY)).isFalse();
        assertThat(signature.isValid("lixo", BODY)).isFalse();
    }

    @Test
    void authenticator_shouldFailClosedWithTheRealProvider() {
        String valid = header(NOW, BODY);
        Duration tolerance = Duration.ofMinutes(5);

        assertThat(new WebhookAuthenticator(Optional.empty(), true, tolerance, CLOCK).accepts(valid, BODY)).isFalse();
        assertThat(new WebhookAuthenticator(Optional.of(" "), true, tolerance, CLOCK).accepts(valid, BODY)).isFalse();
        assertThat(new WebhookAuthenticator(Optional.of(SECRET), true, tolerance, CLOCK).accepts(valid, BODY)).isTrue();
        assertThat(new WebhookAuthenticator(Optional.empty(), false, tolerance, CLOCK).accepts(null, BODY)).isTrue();
        assertThat(new WebhookAuthenticator(Optional.of(SECRET), false, tolerance, CLOCK).accepts(null, BODY)).isFalse();
    }
}
