package br.com.oficina.billing.domain.model;

/** Por onde a decisão chegou (vai no evento para auditoria do OS). */
public enum DecisionChannel {
    /** Link do e-mail, com o token de uso único. */
    CLIENT_TOKEN,
    /** Área do cliente autenticado (JWT emitido pelo Lambda). */
    CLIENT_JWT,
    /** Funcionário registrando a decisão que o cliente deu por telefone. */
    STAFF
}
