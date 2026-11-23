package br.com.oficina.billing.domain;

import static br.com.oficina.billing.domain.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.oficina.billing.domain.event.BillingEvent;
import br.com.oficina.billing.domain.exception.BillingRuleViolation;
import br.com.oficina.billing.domain.exception.BudgetNotFoundException;
import br.com.oficina.billing.domain.model.Budget;
import br.com.oficina.billing.domain.model.BudgetStatus;
import br.com.oficina.billing.domain.model.Customer;
import br.com.oficina.billing.domain.model.Decision;
import br.com.oficina.billing.domain.model.DecisionChannel;
import br.com.oficina.billing.domain.valueobject.ApprovalToken;
import br.com.oficina.billing.domain.valueobject.Money;
import br.com.oficina.billing.domain.valueobject.SagaRef;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BudgetTest {

    @Test
    void issue_shouldSendTheBudgetWithTheSumOfTheLines() {
        SagaRef ref = Fixtures.ref(10);
        Budget.Issued issued = Budget.issue(Fixtures.request(ref), NOW);
        Budget budget = issued.budget();

        assertThat(budget.status()).isEqualTo(BudgetStatus.SENT);
        assertThat(budget.total()).isEqualTo(Fixtures.TOTAL);
        assertThat(budget.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(3)));
        assertThat(budget.snapshot().tokenHash().matches(issued.token())).isTrue();
        assertThat(budget.pullEvents()).containsExactly(
            new BillingEvent.BudgetGenerated(ref, budget.id(), Fixtures.TOTAL, budget.expiresAt()));
        assertThat(budget.pullEvents()).isEmpty();
    }

    @Test
    void issue_shouldRefuseInconsistentRequests() {
        SagaRef ref = Fixtures.ref(11);
        Customer customer = new Customer("Maria", null);
        assertThatThrownBy(() -> Budget.issue(new Budget.Request(ref, 1, customer, List.of(), Money.ZERO,
            Duration.ofDays(1)), NOW)).isInstanceOf(BillingRuleViolation.class).hasMessageContaining("sem itens");
        assertThatThrownBy(() -> Budget.issue(new Budget.Request(ref, 1, customer, Fixtures.lines(), Money.of("400.00"),
            Duration.ofDays(1)), NOW)).isInstanceOf(BillingRuleViolation.class).hasMessageContaining("difere");
        assertThatThrownBy(() -> Budget.issue(new Budget.Request(ref, 1, customer, Fixtures.lines(), Fixtures.TOTAL,
            Duration.ZERO), NOW)).isInstanceOf(BillingRuleViolation.class).hasMessageContaining("Prazo");
        assertThatThrownBy(() -> Budget.issue(new Budget.Request(ref, 1, customer,
            List.of(new br.com.oficina.billing.domain.model.BudgetLine(
                br.com.oficina.billing.domain.model.BudgetLine.ItemKind.SERVICE, 1, "Cortesia", 1, Money.ZERO)),
            Money.ZERO, Duration.ofDays(1)), NOW)).isInstanceOf(BillingRuleViolation.class).hasMessageContaining("zero");
    }

    @Test
    void decide_shouldBeIdempotentForTheSameDecision() {
        Budget budget = Fixtures.issued().budget();

        assertThat(budget.decide(Decision.APPROVE, DecisionChannel.CLIENT_JWT, NOW.plusSeconds(1))).isTrue();
        assertThat(budget.decide(Decision.APPROVE, DecisionChannel.STAFF, NOW.plusSeconds(2))).isFalse();

        assertThat(budget.status()).isEqualTo(BudgetStatus.APPROVED);
        assertThat(budget.decidedBy()).isEqualTo(DecisionChannel.CLIENT_JWT);
        assertThat(budget.decidedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(budget.pullEvents()).containsExactly(
            new BillingEvent.BudgetApproved(budget.ref(), budget.id(), DecisionChannel.CLIENT_JWT));
    }

    @Test
    void decide_contraryDecision_shouldBeARuleViolation() {
        Budget budget = Fixtures.issued().budget();
        budget.decide(Decision.REJECT, DecisionChannel.STAFF, NOW);

        assertThatThrownBy(() -> budget.decide(Decision.APPROVE, DecisionChannel.STAFF, NOW))
            .isInstanceOf(BillingRuleViolation.class).hasMessageContaining("já recusado");
        assertThat(budget.pullEvents()).containsExactly(
            new BillingEvent.BudgetRejected(budget.ref(), budget.id(), DecisionChannel.STAFF));
    }

    @Test
    void decide_afterTheDeadline_shouldBeRefused() {
        Budget budget = Fixtures.issued().budget();

        assertThatThrownBy(() -> budget.decide(Decision.APPROVE, DecisionChannel.STAFF, budget.expiresAt()))
            .isInstanceOf(BillingRuleViolation.class).hasMessageContaining("Prazo");
        assertThat(budget.status()).isEqualTo(BudgetStatus.SENT);
    }

    @Test
    void decideWithToken_shouldAcceptOnlyTheEmailedToken() {
        Budget.Issued issued = Fixtures.issued();
        Budget budget = issued.budget();

        assertThatThrownBy(() -> budget.decideWithToken(ApprovalToken.of("chute"), Decision.APPROVE, NOW))
            .isInstanceOf(BudgetNotFoundException.class);
        assertThat(budget.decideWithToken(issued.token(), Decision.APPROVE, NOW)).isTrue();
        assertThat(budget.decideWithToken(issued.token(), Decision.APPROVE, NOW)).isFalse();
        assertThatThrownBy(() -> budget.decideWithToken(issued.token(), Decision.REJECT, NOW))
            .isInstanceOf(BillingRuleViolation.class);
        assertThat(budget.snapshot().tokenConsumedAt()).isEqualTo(NOW);
        assertThat(budget.decidedBy()).isEqualTo(DecisionChannel.CLIENT_TOKEN);
    }

    @Test
    void expire_shouldOnlyHappenToSentBudgetsPastTheDeadline() {
        Budget budget = Fixtures.issued().budget();

        assertThat(budget.expireIfOverdue(budget.expiresAt().minusSeconds(1))).isFalse();
        assertThat(budget.expireIfOverdue(budget.expiresAt())).isTrue();
        assertThat(budget.expireIfOverdue(budget.expiresAt().plusSeconds(1))).isFalse();
        assertThat(budget.status()).isEqualTo(BudgetStatus.EXPIRED);
        assertThat(budget.pullEvents()).containsExactly(new BillingEvent.BudgetExpired(budget.ref(), budget.id()));

        Budget approved = Fixtures.approved();
        assertThat(approved.expireIfOverdue(Instant.MAX)).isFalse();
    }

    @Test
    void void_shouldAlwaysConfirmSoTheSagaCanMoveOn() {
        Budget budget = Fixtures.approved();

        budget.voidBudget("execução falhou");
        budget.voidBudget("comando repetido");

        assertThat(budget.status()).isEqualTo(BudgetStatus.VOIDED);
        assertThat(budget.snapshot().voidReason()).isEqualTo("execução falhou");
        assertThat(budget.pullEvents()).hasSize(2).allMatch(BillingEvent.BudgetVoided.class::isInstance);
        assertThatThrownBy(() -> budget.decide(Decision.APPROVE, DecisionChannel.STAFF, NOW))
            .isInstanceOf(BillingRuleViolation.class).hasMessageContaining("anulado");
    }

    @Test
    void snapshot_shouldRestoreTheSameBudget() {
        Budget budget = Fixtures.approved();

        Budget restored = Budget.restore(budget.snapshot());

        assertThat(restored.snapshot()).isEqualTo(budget.snapshot());
        assertThat(restored.ownedBy(42)).isTrue();
        assertThat(restored.ownedBy(43)).isFalse();
        assertThat(restored.belongsTo(budget.ref().sagaId())).isTrue();
        assertThat(restored.customer().name()).isEqualTo("Maria");
        assertThat(restored.lines()).hasSize(2);
        restored.repeatGenerated();
        assertThat(restored.pullEvents()).singleElement().isInstanceOf(BillingEvent.BudgetGenerated.class);
    }

    @Test
    void status_shouldTellWhichBudgetsAreActive() {
        assertThat(BudgetStatus.SENT.isActive()).isTrue();
        assertThat(BudgetStatus.APPROVED.isActive()).isTrue();
        assertThat(BudgetStatus.VOIDED.isActive()).isFalse();
        assertThat(BudgetStatus.EXPIRED.isActive()).isFalse();
        assertThat(BudgetStatus.REJECTED.isActive()).isFalse();
    }
}
