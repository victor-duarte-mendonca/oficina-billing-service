package br.com.oficina.billing.domain.model;

import java.time.Instant;
import java.util.Objects;

/** Cobrança Pix criada no provedor: o que o cliente precisa para pagar. */
public record PixCharge(String providerPaymentId, String qrCode, String qrCodeBase64, String ticketUrl, Instant expiresAt) {

    public PixCharge {
        Objects.requireNonNull(providerPaymentId, "providerPaymentId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (providerPaymentId.isBlank()) {
            throw new IllegalArgumentException("Identificador do provedor vazio");
        }
    }
}
