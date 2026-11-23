package br.com.oficina.billing.testsupport;

import br.com.oficina.billing.application.ports.out.BillingMetricsPort;
import br.com.oficina.billing.application.ports.out.BudgetNotifierPort;
import br.com.oficina.billing.domain.model.Budget;
import br.com.oficina.billing.domain.valueobject.ApprovalToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Dublês simples (sem Mockito: no JDK 25 local ele não instrumenta todas as interfaces). */
public final class TestDoubles {

    private TestDoubles() {
    }

    /** Relógio que só anda quando o teste manda. */
    public static final class MutableClock extends Clock {

        private Instant now;

        public MutableClock(Instant start) {
            this.now = start;
        }

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    public static final class RecordingMetrics implements BillingMetricsPort {

        public final List<String> events = new ArrayList<>();
        public final List<String> gatewayFailures = new ArrayList<>();
        public int webhookRejections;

        @Override
        public void eventPublished(String type) {
            events.add(type);
        }

        @Override
        public void gatewayFailure(String operation) {
            gatewayFailures.add(operation);
        }

        @Override
        public void webhookRejected() {
            webhookRejections++;
        }
    }

    /** Guarda o token entregue ao cliente, como faria a caixa de e-mail. */
    public static final class RecordingNotifier implements BudgetNotifierPort {

        public final List<ApprovalToken> tokens = new ArrayList<>();

        @Override
        public void budgetIssued(Budget budget, ApprovalToken token) {
            tokens.add(token);
        }

        public String lastToken() {
            return tokens.get(tokens.size() - 1).value();
        }
    }
}
