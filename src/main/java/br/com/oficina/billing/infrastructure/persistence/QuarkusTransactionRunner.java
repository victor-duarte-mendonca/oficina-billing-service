package br.com.oficina.billing.infrastructure.persistence;

import br.com.oficina.billing.application.ports.out.TransactionRunner;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.function.Supplier;

/**
 * Cada chamada é uma transação nova e independente: os casos de uso que falam com o provedor de pagamento precisam
 * confirmar a primeira transação antes da chamada HTTP e abrir outra depois.
 */
@ApplicationScoped
public class QuarkusTransactionRunner implements TransactionRunner {

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return QuarkusTransaction.requiringNew().call(work::get);
    }
}
