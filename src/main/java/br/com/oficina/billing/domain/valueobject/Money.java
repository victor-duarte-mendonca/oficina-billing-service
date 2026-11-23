package br.com.oficina.billing.domain.valueobject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Valor em reais com exatamente duas casas. Nunca ponto flutuante: o contrato trafega {@code "450.00"} como
 * texto e a comparação é por valor ({@code 450.0} e {@code 450.00} são o mesmo dinheiro).
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Valor negativo não é dinheiro válido: " + amount);
        }
        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Valor com mais de duas casas decimais: " + amount, e);
        }
    }

    public static Money of(String value) {
        try {
            return new Money(new BigDecimal(Objects.requireNonNull(value, "value")));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valor monetário inválido: " + value, e);
        }
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money times(int quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)));
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    /** Formato do contrato ({@code envelope.schema.json#/$defs/money}). */
    public String toWire() {
        return amount.toPlainString();
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return toWire();
    }
}
