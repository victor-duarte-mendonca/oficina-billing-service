package br.com.oficina.billing.application.ports.out;

import java.util.function.Supplier;

/**
 * Fronteira transacional explícita. Os casos de uso que falam com o provedor de pagamento não podem segurar uma
 * transação (e uma conexão do pool) durante a chamada HTTP: gravam a intenção numa transação, chamam o provedor
 * fora dela e aplicam o resultado numa segunda transação.
 */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);

    default void run(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }
}
