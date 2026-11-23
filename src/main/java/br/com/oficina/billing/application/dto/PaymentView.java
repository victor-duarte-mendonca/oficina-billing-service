package br.com.oficina.billing.application.dto;

import br.com.oficina.billing.domain.model.Payment;
import br.com.oficina.billing.domain.model.PixCharge;
import java.time.Instant;
import java.util.Optional;

/** Cobrança Pix: o "copia e cola", o QR Code em base64 e o prazo. Sem dados do pagador. */
public record PaymentView(String paymentId, int attempt, String status, String amount, String qrCode,
                          String qrCodeBase64, String ticketUrl, Instant expiresAt, String detail) {

    public static PaymentView of(Payment payment) {
        Optional<PixCharge> charge = Optional.ofNullable(payment.charge());
        return new PaymentView(payment.id().toString(), payment.attempt(), payment.status().name(),
            payment.amount().toWire(),
            charge.map(PixCharge::qrCode).orElse(null),
            charge.map(PixCharge::qrCodeBase64).orElse(null),
            charge.map(PixCharge::ticketUrl).orElse(null),
            charge.map(PixCharge::expiresAt).orElse(null),
            payment.failureDetail());
    }
}
