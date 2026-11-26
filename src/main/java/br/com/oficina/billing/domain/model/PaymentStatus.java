package br.com.oficina.billing.domain.model;

/**
 * Ciclo do pagamento Pix.
 *
 * <pre>
 * REQUESTED → PENDING → APPROVED | REJECTED | EXPIRED
 * REQUESTED → FAILED                         (provedor recusou ou indisponível)
 * APPROVED  → REFUND_PENDING → REFUNDED | REFUND_FAILED → REFUND_PENDING (nova tentativa)
 * REJECTED | EXPIRED → APPROVED              (pagamento que chegou depois: a Saga trata como órfão)
 * </pre>
 *
 * {@code REQUESTED} existe para gravar a chave de idempotência <b>antes</b> de chamar o provedor (spec 05 §4, V6):
 * se o pod cair no meio da chamada, a reentrega do comando reusa a mesma chave e a Stripe não cobra duas vezes.
 */
public enum PaymentStatus {
    REQUESTED, PENDING, APPROVED, REJECTED, EXPIRED, FAILED, REFUND_PENDING, REFUNDED, REFUND_FAILED;

    /** Ativo = conta para "um pagamento vivo por orçamento" (índice único parcial no banco). */
    public boolean isActive() {
        return this == REQUESTED || this == PENDING || this == APPROVED || this == REFUND_PENDING;
    }
}
