package br.com.oficina.billing.infrastructure.payment;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verificação do cabeçalho {@code x-signature} do webhook ({@code ts=<epoch>,v1=<hex>}).
 *
 * <p>Manifesto: {@code id:<data.id>;request-id:<x-request-id>;ts:<ts>;}, HMAC-SHA256 com o segredo do webhook, em
 * hexadecimal; partes ausentes são omitidas e o {@code data.id} alfanumérico vai em minúsculas. <b>Pendência V3 da
 * spec 05:</b> a página da documentação lida em 2026-11-23 confirma só o formato {@code ts}/{@code v1}; o manifesto
 * segue a descrição "sem SDK" do Mercado Pago e precisa ser confirmado no sandbox. Por isso a assinatura é a primeira
 * barreira, não a única: o serviço nunca confia no corpo e sempre reconsulta o pagamento na API.</p>
 */
public final class MercadoPagoSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public MercadoPagoSignature(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(String signatureHeader, String requestId, String dataId) {
        Optional<String> ts = part(signatureHeader, "ts");
        Optional<String> v1 = part(signatureHeader, "v1");
        if (ts.isEmpty() || v1.isEmpty()) {
            return false;
        }
        String expected = sign(manifest(dataId, requestId, ts.get()));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
            v1.get().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    static String manifest(String dataId, String requestId, String ts) {
        StringBuilder manifest = new StringBuilder();
        if (dataId != null && !dataId.isBlank()) {
            manifest.append("id:").append(dataId.matches("[A-Za-z0-9]+") ? dataId.toLowerCase(Locale.ROOT) : dataId)
                .append(';');
        }
        if (requestId != null && !requestId.isBlank()) {
            manifest.append("request-id:").append(requestId).append(';');
        }
        return manifest.append("ts:").append(ts).append(';').toString();
    }

    String sign(String manifest) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponível", e);
        }
    }

    private static Optional<String> part(String header, String name) {
        if (header == null) {
            return Optional.empty();
        }
        for (String piece : header.split(",")) {
            String[] keyValue = piece.trim().split("=", 2);
            if (keyValue.length == 2 && keyValue[0].trim().equals(name) && !keyValue[1].isBlank()) {
                return Optional.of(keyValue[1].trim());
            }
        }
        return Optional.empty();
    }
}
