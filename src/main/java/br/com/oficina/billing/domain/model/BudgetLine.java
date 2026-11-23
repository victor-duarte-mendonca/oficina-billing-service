package br.com.oficina.billing.domain.model;

import br.com.oficina.billing.domain.exception.BillingRuleViolation;
import br.com.oficina.billing.domain.valueobject.Money;
import java.util.Objects;

/** Linha do orçamento (peça ou serviço) com o preço do momento em que o orçamento foi emitido. */
public record BudgetLine(ItemKind kind, long refId, String name, int quantity, Money unitPrice) {

    public BudgetLine {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (name == null || name.isBlank()) {
            throw new BillingRuleViolation("Item do orçamento sem nome");
        }
        if (quantity < 1) {
            throw new BillingRuleViolation("Quantidade inválida para '" + name + "': " + quantity);
        }
        if (refId < 1) {
            throw new BillingRuleViolation("Referência inválida para '" + name + "'");
        }
    }

    public Money subtotal() {
        return unitPrice.times(quantity);
    }

    public enum ItemKind { PART, SERVICE }
}
