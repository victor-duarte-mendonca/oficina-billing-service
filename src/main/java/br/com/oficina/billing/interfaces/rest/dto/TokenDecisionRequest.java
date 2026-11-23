package br.com.oficina.billing.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Decisão pelo link do e-mail. O {@code cpfCnpj} é aceito por compatibilidade com o fluxo da Fase 3, mas não é
 * prova de nada (dado conhecido): a autorização é só o token de uso único.
 */
public record TokenDecisionRequest(@NotBlank @Size(max = 100) String token, @Size(max = 18) String cpfCnpj) {
}
