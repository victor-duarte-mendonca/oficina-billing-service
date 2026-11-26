package br.com.oficina.billing.infrastructure.payment;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verificação do cabeçalho {@code Stripe-Signature} (documentação da Stripe, "Verify webhook signatures manually"):
 * <ol>
 *   <li>{@code t} e todos os {@code v1} do cabeçalho; o resto (inclusive {@code v0}, só do sandbox, e os campos de
 *       depuração {@code secret}/{@code destination}/{@code payload}) é ignorado;</li>
 *   <li>{@code signed_payload = t + "." + corpo cru, sem nenhuma alteração};</li>
 *   <li>HMAC-SHA256 com o segredo do endpoint ({@code whsec_…}), em hexadecimal;</li>
 *   <li>comparação em tempo constante com cada {@code v1} (a rotação de segredo envia mais de um);</li>
 *   <li>tolerância de tempo contra <i>replay</i> (padrão 5 min; zero desligaria a checagem, então é recusado).</li>
 * </ol>
 */
public final class StripeSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final Duration tolerance;
    private final Clock clock;

    public StripeSignature(String secret, Duration tolerance, Clock clock) {
        if (tolerance.isZero() || tolerance.isNegative()) {
            throw new IllegalArgumentException("A tolerância do webhook deve ser positiva");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tolerance = tolerance;
        this.clock = clock;
    }

    public boolean isValid(String signatureHeader, String rawBody) {
        if (signatureHeader == null || rawBody == null) {
            return false;
        }
        Optional<Long> timestamp = timestamp(signatureHeader);
        List<String> candidates = signatures(signatureHeader);
        if (timestamp.isEmpty() || candidates.isEmpty()) {
            return false;
        }
        byte[] expected = sign(secret, timestamp.get(), rawBody).getBytes(StandardCharsets.US_ASCII);
        boolean matches = false;
        for (String candidate : candidates) {
            // sem "return" antecipado: o tempo não revela qual candidato casou
            matches |= MessageDigest.isEqual(expected,
                candidate.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
        }
        return matches && withinTolerance(timestamp.get());
    }

    /** Assina como a Stripe faria; usado pelos testes para montar notificações válidas. */
    public static String sign(String secret, long timestamp, String rawBody) {
        return sign(secret.getBytes(StandardCharsets.UTF_8), timestamp, rawBody);
    }

    private static String sign(byte[] secret, long timestamp, String rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + rawBody).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponível", e);
        }
    }

    private boolean withinTolerance(long timestamp) {
        long skew = Math.abs(clock.instant().getEpochSecond() - timestamp);
        return skew <= tolerance.getSeconds();
    }

    private static Optional<Long> timestamp(String header) {
        for (String piece : header.split(",")) {
            String[] keyValue = piece.trim().split("=", 2);
            if (keyValue.length == 2 && keyValue[0].trim().equals("t")) {
                try {
                    return Optional.of(Long.parseLong(keyValue[1].trim()));
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static List<String> signatures(String header) {
        List<String> values = new ArrayList<>();
        for (String piece : header.split(",")) {
            String[] keyValue = piece.trim().split("=", 2);
            if (keyValue.length == 2 && keyValue[0].trim().equals("v1") && !keyValue[1].isBlank()) {
                values.add(keyValue[1].trim());
            }
        }
        return values;
    }
}
