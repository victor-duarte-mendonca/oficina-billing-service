package br.com.oficina.billing.application.ports.out;

import br.com.oficina.billing.domain.model.Budget;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Orçamentos persistidos. {@link #save} usa a versão do agregado (concorrência otimista). */
public interface BudgetRepository {

    Optional<Budget> findById(UUID id);

    /** O orçamento mais recente da OS (os anteriores foram anulados em ciclos de Saga passados). */
    Optional<Budget> findLatestByOrderNumber(String orderNumber);

    /** O orçamento vivo ({@code SENT}/{@code APPROVED}) da OS, se houver. */
    Optional<Budget> findActiveByWorkOrder(long workOrderId);

    /** Orçamentos enviados cujo prazo de decisão venceu. */
    List<Budget> findOverdue(Instant now, int limit);

    void save(Budget budget);
}
