package br.com.oficina.billing.domain.valueobject;

import java.util.Objects;
import java.util.UUID;

/** A quem as respostas pertencem: a Saga do OS, a OS e o número exibido ao cliente. */
public record SagaRef(UUID sagaId, long workOrderId, String orderNumber) {

    public SagaRef {
        Objects.requireNonNull(sagaId, "sagaId");
        if (workOrderId < 1) {
            throw new IllegalArgumentException("workOrderId inválido: " + workOrderId);
        }
    }
}
