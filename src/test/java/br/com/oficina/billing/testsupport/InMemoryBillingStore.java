package br.com.oficina.billing.testsupport;

import br.com.oficina.billing.application.messaging.OutboundMessage;
import br.com.oficina.billing.application.ports.out.BudgetRepository;
import br.com.oficina.billing.application.ports.out.ConcurrentUpdateException;
import br.com.oficina.billing.application.ports.out.InboxPort;
import br.com.oficina.billing.application.ports.out.OutboxPort;
import br.com.oficina.billing.application.ports.out.PaymentRepository;
import br.com.oficina.billing.application.ports.out.TransactionRunner;
import br.com.oficina.billing.domain.model.Budget;
import br.com.oficina.billing.domain.model.BudgetStatus;
import br.com.oficina.billing.domain.model.Payment;
import br.com.oficina.billing.domain.model.PaymentStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Banco em memória com a mesma semântica observável do PostgreSQL: grava cópias (snapshots), incrementa a versão,
 * recusa versão antiga e um segundo pagamento ativo por orçamento. Transação = execução direta.
 */
public final class InMemoryBillingStore implements OutboxPort, InboxPort, TransactionRunner {

    private final Map<UUID, Budget.Snapshot> budgets = new LinkedHashMap<>();
    private final Map<UUID, Payment.Snapshot> payments = new LinkedHashMap<>();
    private final Set<UUID> inbox = new HashSet<>();
    public final List<OutboundMessage> outbox = new ArrayList<>();
    public final List<String> notices = new ArrayList<>();
    public final Budgets budgetRepository = new Budgets();
    public final Payments paymentRepository = new Payments();

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return work.get();
    }

    /** Orçamentos. */
    public final class Budgets implements BudgetRepository {

    @Override
    public Optional<Budget> findById(UUID id) {
        return Optional.ofNullable(budgets.get(id)).map(Budget::restore);
    }

    @Override
    public Optional<Budget> findLatestByOrderNumber(String orderNumber) {
        return budgets.values().stream().filter(b -> orderNumber.equals(b.ref().orderNumber()))
            .max(Comparator.comparing(Budget.Snapshot::createdAt)).map(Budget::restore);
    }

    @Override
    public Optional<Budget> findActiveByWorkOrder(long workOrderId) {
        return budgets.values().stream()
            .filter(b -> b.ref().workOrderId() == workOrderId && b.status().isActive())
            .findFirst().map(Budget::restore);
    }

    @Override
    public List<Budget> findOverdue(Instant now, int limit) {
        return budgets.values().stream()
            .filter(b -> b.status() == BudgetStatus.SENT && !now.isBefore(b.expiresAt()))
            .limit(limit).map(Budget::restore).toList();
    }

    @Override
    public void save(Budget budget) {
        Budget.Snapshot s = budget.snapshot();
        checkVersion(budgets.get(s.id()) == null ? null : budgets.get(s.id()).version(), s.version(), s.id());
        budgets.put(s.id(), new Budget.Snapshot(s.id(), s.ref(), s.clientId(), s.customer(), s.lines(), s.total(),
            s.status(), s.tokenHash(), s.tokenConsumedAt(), s.decidedBy(), s.decidedAt(), s.voidReason(), s.createdAt(),
            s.expiresAt(), s.version() + (budgets.containsKey(s.id()) ? 1 : 0)));
    }

    }

    /** Pagamentos. */
    public final class Payments implements PaymentRepository {

    @Override
    public Optional<Payment> findById(UUID id) {
        return Optional.ofNullable(payments.get(id)).map(Payment::restore);
    }

    @Override
    public Optional<Payment> findByProviderId(String providerPaymentId) {
        return payments.values().stream()
            .filter(p -> p.charge() != null && providerPaymentId.equals(p.charge().providerPaymentId()))
            .findFirst().map(Payment::restore);
    }

    @Override
    public Optional<Payment> findByBudgetAndAttempt(UUID budgetId, int attempt) {
        return payments.values().stream().filter(p -> p.budgetId().equals(budgetId) && p.attempt() == attempt)
            .findFirst().map(Payment::restore);
    }

    @Override
    public List<Payment> findByBudget(UUID budgetId) {
        return payments.values().stream().filter(p -> p.budgetId().equals(budgetId))
            .sorted(Comparator.comparingInt(Payment.Snapshot::attempt).reversed()).map(Payment::restore).toList();
    }

    @Override
    public List<Payment> findPendingCreatedBefore(Instant createdBefore, int limit) {
        return byStatus(PaymentStatus.PENDING, createdBefore, limit);
    }

    @Override
    public List<Payment> findAwaitingProviderCreatedBefore(Instant createdBefore, int limit) {
        return byStatus(PaymentStatus.REQUESTED, createdBefore, limit);
    }

    @Override
    public void save(Payment payment) {
        Payment.Snapshot s = payment.snapshot();
        Payment.Snapshot stored = payments.get(s.id());
        checkVersion(stored == null ? null : stored.version(), s.version(), s.id());
        boolean otherActive = payments.values().stream().anyMatch(p -> !p.id().equals(s.id())
            && p.budgetId().equals(s.budgetId()) && p.status().isActive());
        if (s.status().isActive() && otherActive) {
            throw new ActivePaymentConflictException(s.budgetId(), null);
        }
        payments.put(s.id(), new Payment.Snapshot(s.id(), s.budgetId(), s.ref(), s.attempt(), s.amount(),
            s.idempotencyKey(), s.createdAt(), s.status(), s.charge(), s.failureDetail(), s.refundAttempts(),
            s.version() + (stored == null ? 0 : 1)));
    }

    @Override
    public void recordProviderNotice(UUID paymentId, NoticeSource source, String providerStatus, Instant receivedAt) {
        notices.add(source + ":" + providerStatus);
    }
    }

    // ---- outbox / inbox

    @Override
    public void enqueue(OutboundMessage message) {
        outbox.add(message);
    }

    @Override
    public boolean markProcessed(UUID messageId) {
        return inbox.add(messageId);
    }

    @Override
    public boolean wasProcessed(UUID messageId) {
        return inbox.contains(messageId);
    }

    public List<String> outboxTypes() {
        return outbox.stream().map(OutboundMessage::type).toList();
    }

    public OutboundMessage lastMessage() {
        return outbox.get(outbox.size() - 1);
    }

    private List<Payment> byStatus(PaymentStatus status, Instant createdBefore, int limit) {
        return payments.values().stream().filter(p -> p.status() == status && p.createdAt().isBefore(createdBefore))
            .limit(limit).map(Payment::restore).toList();
    }

    private static void checkVersion(Long stored, long given, UUID id) {
        if (stored != null && stored != given) {
            throw new ConcurrentUpdateException("Agregado", id, null);
        }
    }
}
