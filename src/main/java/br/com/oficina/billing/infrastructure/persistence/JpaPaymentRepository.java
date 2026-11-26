package br.com.oficina.billing.infrastructure.persistence;

import br.com.oficina.billing.application.ports.out.ConcurrentUpdateException;
import br.com.oficina.billing.application.ports.out.PaymentRepository;
import br.com.oficina.billing.domain.model.Payment;
import br.com.oficina.billing.domain.model.PaymentStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;

/** Pagamentos no PostgreSQL. A regra "um pagamento ativo por orçamento" é garantida pelo índice parcial. */
@ApplicationScoped
public class JpaPaymentRepository implements PaymentRepository {

    /** Limite superior para listar por situação sem filtrar por idade. */
    private static final Instant FAR_FUTURE = Instant.parse("9999-01-01T00:00:00Z");

    static final String ACTIVE_PAYMENT_INDEX = "ux_payment_active_budget";

    private final EntityManager em;

    public JpaPaymentRepository(EntityManager em) {
        this.em = em;
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return Optional.ofNullable(em.find(PaymentEntity.class, id)).map(PaymentEntity::toDomain);
    }

    @Override
    public Optional<Payment> findByProviderId(String providerPaymentId) {
        return single("FROM PaymentEntity p WHERE p.providerPaymentId = ?1", providerPaymentId);
    }

    @Override
    public Optional<Payment> findByBudgetAndAttempt(UUID budgetId, int attempt) {
        return em.createQuery("FROM PaymentEntity p WHERE p.budgetId = ?1 AND p.attempt = ?2", PaymentEntity.class)
            .setParameter(1, budgetId).setParameter(2, attempt)
            .getResultStream().findFirst().map(PaymentEntity::toDomain);
    }

    @Override
    public List<Payment> findByBudget(UUID budgetId) {
        return em.createQuery("FROM PaymentEntity p WHERE p.budgetId = ?1 ORDER BY p.attempt DESC", PaymentEntity.class)
            .setParameter(1, budgetId)
            .getResultStream().map(PaymentEntity::toDomain).toList();
    }

    @Override
    public List<Payment> findPendingCreatedBefore(Instant createdBefore, int limit) {
        return byStatusCreatedBefore(PaymentStatus.PENDING, createdBefore, limit);
    }

    @Override
    public List<Payment> findAwaitingProviderCreatedBefore(Instant createdBefore, int limit) {
        return byStatusCreatedBefore(PaymentStatus.REQUESTED, createdBefore, limit);
    }

    @Override
    public List<Payment> findRefundPending(int limit) {
        return byStatusCreatedBefore(PaymentStatus.REFUND_PENDING, FAR_FUTURE, limit);
    }

    @Override
    public void save(Payment payment) {
        Payment.Snapshot state = payment.snapshot();
        PaymentEntity entity = em.find(PaymentEntity.class, state.id());
        if (entity == null) {
            em.persist(PaymentEntity.create(state));
        } else if (entity.version() != state.version()) {
            throw new ConcurrentUpdateException("Pagamento", state.id(), null);
        } else {
            entity.update(state);
        }
        flush(state);
    }

    @Override
    public void recordProviderNotice(UUID paymentId, NoticeSource source, String providerStatus, Instant receivedAt) {
        em.createNativeQuery("INSERT INTO payment_event (payment_id, source, provider_status, received_at) "
                + "VALUES (?1, ?2, ?3, ?4)")
            .setParameter(1, paymentId)
            .setParameter(2, source.name())
            .setParameter(3, providerStatus)
            .setParameter(4, receivedAt)
            .executeUpdate();
    }

    /** O índice parcial só é avaliado no {@code INSERT}/{@code UPDATE}: o flush traz a violação para cá. */
    private void flush(Payment.Snapshot state) {
        try {
            em.flush();
        } catch (PersistenceException e) {
            if (violates(e, ACTIVE_PAYMENT_INDEX)) {
                throw new ActivePaymentConflictException(state.budgetId(), e);
            }
            throw e;
        }
    }

    /** O Hibernate 6 lança a violação diretamente ou embrulhada, conforme o ponto do flush. */
    static boolean violates(Throwable error, String constraint) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException violation && violation.getConstraintName() != null
                    && violation.getConstraintName().contains(constraint)) {
                return true;
            }
        }
        return false;
    }

    private List<Payment> byStatusCreatedBefore(PaymentStatus status, Instant createdBefore, int limit) {
        return em.createQuery("FROM PaymentEntity p WHERE p.status = ?1 AND p.createdAt < ?2 ORDER BY p.createdAt",
                PaymentEntity.class)
            .setParameter(1, status.name()).setParameter(2, createdBefore)
            .setMaxResults(limit)
            .getResultStream().map(PaymentEntity::toDomain).toList();
    }

    private Optional<Payment> single(String query, Object parameter) {
        return em.createQuery(query, PaymentEntity.class).setParameter(1, parameter)
            .getResultStream().findFirst().map(PaymentEntity::toDomain);
    }

}
