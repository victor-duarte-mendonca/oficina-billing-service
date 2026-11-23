package br.com.oficina.billing.domain.exception;

/** Regra de negócio recusou a operação (decisão fora do prazo, valor diferente do orçamento, ...). */
public class BillingRuleViolation extends RuntimeException {

    public BillingRuleViolation(String message) {
        super(message);
    }
}
