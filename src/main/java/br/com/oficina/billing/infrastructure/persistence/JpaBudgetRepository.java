package br.com.oficina.billing.infrastructure.persistence;

import br.com.oficina.billing.application.ports.out.BudgetRepository;
import br.com.oficina.billing.application.ports.out.ConcurrentUpdateException;
import br.com.oficina.billing.domain.model.Budget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Orçamentos no PostgreSQL. Grava pelo {@link Budget.Snapshot} (o agregado não conhece JPA). */
@ApplicationScoped
public class JpaBudgetRepository implements BudgetRepository {

    /** Hint do Hibernate para {@code FOR UPDATE SKIP LOCKED}: réplicas do job não pegam o mesmo orçamento. */
    static final String LOCK_TIMEOUT = "jakarta.persistence.lock.timeout";
    static final int SKIP_LOCKED = -2;

    private final EntityManager em;

    public JpaBudgetRepository(EntityManager em) {
        this.em = em;
    }

    @Override
    public Optional<Budget> findById(UUID id) {
        return Optional.ofNullable(em.find(BudgetEntity.class, id)).map(BudgetEntity::toDomain);
    }

    @Override
    public Optional<Budget> findLatestByOrderNumber(String orderNumber) {
        return em.createQuery("FROM BudgetEntity b WHERE b.orderNumber = :number ORDER BY b.createdAt DESC",
                BudgetEntity.class)
            .setParameter("number", orderNumber)
            .setMaxResults(1)
            .getResultStream().findFirst().map(BudgetEntity::toDomain);
    }

    @Override
    public Optional<Budget> findActiveByWorkOrder(long workOrderId) {
        return em.createQuery("FROM BudgetEntity b WHERE b.workOrderId = :wo AND b.status IN ('SENT', 'APPROVED')",
                BudgetEntity.class)
            .setParameter("wo", workOrderId)
            .getResultStream().findFirst().map(BudgetEntity::toDomain);
    }

    @Override
    public List<Budget> findOverdue(Instant now, int limit) {
        return em.createQuery("FROM BudgetEntity b WHERE b.status = 'SENT' AND b.expiresAt <= :now ORDER BY b.expiresAt",
                BudgetEntity.class)
            .setParameter("now", now)
            .setMaxResults(limit)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .setHint(LOCK_TIMEOUT, SKIP_LOCKED)
            .getResultStream().map(BudgetEntity::toDomain).toList();
    }

    @Override
    public void save(Budget budget) {
        Budget.Snapshot state = budget.snapshot();
        BudgetEntity entity = em.find(BudgetEntity.class, state.id());
        if (entity == null) {
            em.persist(BudgetEntity.create(state));
        } else if (entity.version() != state.version()) {
            throw new ConcurrentUpdateException("Orçamento", state.id(), null);
        } else {
            entity.update(state);
        }
        em.flush();
    }
}
