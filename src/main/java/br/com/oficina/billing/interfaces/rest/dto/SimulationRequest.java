package br.com.oficina.billing.interfaces.rest.dto;

import br.com.oficina.billing.domain.model.ProviderStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** O que o provedor simulado deve passar a informar sobre o Pix. */
public record SimulationRequest(@NotNull ProviderStatus status, @Size(max = 100) String detail) {
}
