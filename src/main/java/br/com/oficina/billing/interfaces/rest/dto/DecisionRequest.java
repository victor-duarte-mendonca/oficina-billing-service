package br.com.oficina.billing.interfaces.rest.dto;

import br.com.oficina.billing.domain.model.Decision;
import jakarta.validation.constraints.NotNull;

/** Decisão do cliente autenticado: {@code {"decision": "APPROVE" | "REJECT"}}. */
public record DecisionRequest(@NotNull Decision decision) {
}
