package br.com.oficina.billing.application.ports.in;

/** Trabalho periódico: a Saga não pode depender só de eventos externos que podem não chegar. */
public interface BillingMaintenanceUseCase {

    /** Orçamentos sem decisão dentro do prazo → {@code BudgetExpired}. @return quantos expiraram */
    int expireOverdueBudgets();

    /**
     * Pix pendentes há mais que o limite são consultados no provedor (webhook perdido, ou o Lab sem endereço público
     * estável); pedidos que ficaram sem resposta do provedor são encerrados. @return quantos pagamentos mudaram
     */
    int reconcilePayments();
}
