package br.com.oficina.billing.domain.model;

import br.com.oficina.billing.domain.exception.BillingRuleViolation;
import java.util.Optional;

/**
 * O mínimo do cliente para enviar o orçamento (LGPD, spec 02 §3): nome e e-mail. O CPF/CNPJ fica no OS Service.
 */
public record Customer(String name, String email) {

    public Customer {
        if (name == null || name.isBlank()) {
            throw new BillingRuleViolation("Cliente sem nome");
        }
        email = email == null || email.isBlank() ? null : email.trim();
    }

    public Optional<String> optionalEmail() {
        return Optional.ofNullable(email);
    }
}
