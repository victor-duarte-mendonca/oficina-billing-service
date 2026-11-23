package br.com.oficina.billing.domain.valueobject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Segredo de uso único que autoriza a decisão do orçamento pelo link do e-mail (mesma regra do OS da Fase 3):
 * o número da OS é sequencial e o CPF é conhecido, então só um segredo aleatório de 256 bits protege a rota
 * pública. Existe em claro apenas no e-mail; o banco guarda o {@link #hash()}.
 */
public final class ApprovalToken {

    private static final int BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String value;

    private ApprovalToken(String value) {
        this.value = value;
    }

    public static ApprovalToken generate() {
        byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return new ApprovalToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    /** Token informado pelo cliente; vazio ou nulo nunca confere. */
    public static ApprovalToken of(String candidate) {
        return new ApprovalToken(candidate == null ? "" : candidate);
    }

    public String value() {
        return value;
    }

    /** SHA-256 em hexadecimal: o que vai para o banco. */
    public TokenHash hash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return new TokenHash(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }

    /** O valor em claro nunca vai para log nem para {@code toString}. */
    @Override
    public String toString() {
        return "ApprovalToken[***]";
    }

    /** Hash persistido; a comparação é em tempo constante (não revela quantos bytes batem). */
    public record TokenHash(String hex) {

        public TokenHash {
            Objects.requireNonNull(hex, "hex");
            if (!hex.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Hash de token inválido");
            }
        }

        public boolean matches(ApprovalToken candidate) {
            return candidate != null && !candidate.value.isEmpty()
                && MessageDigest.isEqual(hex.getBytes(StandardCharsets.US_ASCII),
                    candidate.hash().hex().getBytes(StandardCharsets.US_ASCII));
        }
    }
}
