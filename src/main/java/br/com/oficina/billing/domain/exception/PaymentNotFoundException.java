package br.com.oficina.billing.domain.exception;

/** Nenhuma cobrança para o orçamento consultado (ainda não aprovado, ou o Pix ainda não foi pedido). */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String orderNumber) {
        super("Nenhum pagamento para a OS " + orderNumber);
    }
}
