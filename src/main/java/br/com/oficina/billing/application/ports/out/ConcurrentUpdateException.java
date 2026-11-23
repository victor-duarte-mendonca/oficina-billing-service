package br.com.oficina.billing.application.ports.out;

/** Outro processo alterou o agregado depois da leitura (trava otimista pela versão). */
public class ConcurrentUpdateException extends RuntimeException {

    public ConcurrentUpdateException(String aggregate, Object id, Throwable cause) {
        super(aggregate + " " + id + " foi alterado por outro processo", cause);
    }
}
