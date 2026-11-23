package br.com.oficina.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.oficina.billing.domain.exception.BillingRuleViolation;
import br.com.oficina.billing.domain.model.BudgetLine;
import br.com.oficina.billing.domain.model.Customer;
import br.com.oficina.billing.domain.model.PixCharge;
import br.com.oficina.billing.domain.valueobject.ApprovalToken;
import br.com.oficina.billing.domain.valueobject.ApprovalToken.TokenHash;
import br.com.oficina.billing.domain.valueobject.Money;
import br.com.oficina.billing.domain.valueobject.SagaRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValueObjectsTest {

    @Test
    void money_shouldCompareByValueAndKeepTwoDecimals() {
        assertThat(Money.of("450")).isEqualTo(Money.of("450.00"));
        assertThat(Money.of("100.00").times(2).plus(Money.of("250.00")).toWire()).isEqualTo("450.00");
        assertThat(Money.of("0.00").isPositive()).isFalse();
        assertThat(Money.of("10.5")).hasToString("10.50");
    }

    @Test
    void money_shouldRefuseWhatIsNotMoney() {
        assertThatThrownBy(() -> Money.of("-1.00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("1.001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("dez")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(null)).isInstanceOf(NullPointerException.class);
        assertThat(new Money(new BigDecimal("3"))).isEqualTo(Money.of("3.00"));
    }

    @Test
    void token_shouldBeRandomAndComparedOnlyByHash() {
        ApprovalToken token = ApprovalToken.generate();
        TokenHash hash = token.hash();

        assertThat(token.value()).hasSize(43);
        assertThat(ApprovalToken.generate().value()).isNotEqualTo(token.value());
        assertThat(hash.matches(ApprovalToken.of(token.value()))).isTrue();
        assertThat(hash.matches(ApprovalToken.of(token.value() + "x"))).isFalse();
        assertThat(hash.matches(ApprovalToken.of(null))).isFalse();
        assertThat(hash.matches(null)).isFalse();
        assertThat(token.toString()).doesNotContain(token.value());
        assertThatThrownBy(() -> new TokenHash("abc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void line_shouldValidateItsFields() {
        assertThat(new BudgetLine(BudgetLine.ItemKind.PART, 1, "Filtro", 3, Money.of("10.00")).subtotal())
            .isEqualTo(Money.of("30.00"));
        assertThatThrownBy(() -> new BudgetLine(BudgetLine.ItemKind.PART, 1, " ", 1, Money.ZERO))
            .isInstanceOf(BillingRuleViolation.class);
        assertThatThrownBy(() -> new BudgetLine(BudgetLine.ItemKind.PART, 1, "Filtro", 0, Money.ZERO))
            .isInstanceOf(BillingRuleViolation.class);
        assertThatThrownBy(() -> new BudgetLine(BudgetLine.ItemKind.PART, 0, "Filtro", 1, Money.ZERO))
            .isInstanceOf(BillingRuleViolation.class);
    }

    @Test
    void customer_shouldNormalizeTheEmail() {
        assertThat(new Customer("Ana", "  ").optionalEmail()).isEmpty();
        assertThat(new Customer("Ana", " ana@x.com ").email()).isEqualTo("ana@x.com");
        assertThatThrownBy(() -> new Customer(null, null)).isInstanceOf(BillingRuleViolation.class);
    }

    @Test
    void references_shouldBeValid() {
        assertThatThrownBy(() -> new SagaRef(UUID.randomUUID(), 0, "OS-000000")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PixCharge(" ", null, null, null, Instant.now()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
