package br.com.oficina.billing.testsupport;

import br.com.oficina.billing.application.ports.in.BudgetUseCase;
import br.com.oficina.billing.application.ports.in.HandleBillingCommandUseCase;
import br.com.oficina.billing.application.ports.in.PaymentSyncUseCase;
import br.com.oficina.billing.application.ports.out.PaymentRepository.NoticeSource;
import br.com.oficina.billing.domain.model.Decision;
import br.com.oficina.billing.domain.model.ProviderStatus;
import br.com.oficina.billing.infrastructure.payment.SimulatedPaymentGateway;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Monta o estado dos testes {@code @QuarkusTest} pelos casos de uso reais (banco, outbox, e-mail simulado): cada
 * cenário usa uma OS nova, então os testes não dependem de ordem.
 */
@ApplicationScoped
public class BillingScenario {

    private static final AtomicLong WORK_ORDERS = new AtomicLong(9_000);
    private static final Pattern TOKEN = Pattern.compile("Código de autorização: (\\S+)");

    @Inject HandleBillingCommandUseCase commands;
    @Inject BudgetUseCase budgets;
    @Inject PaymentSyncUseCase sync;
    @Inject SimulatedPaymentGateway gateway;
    @Inject MockMailbox mailbox;

    public Order newOrder() {
        long workOrderId = WORK_ORDERS.incrementAndGet();
        return new Order(UUID.randomUUID(), workOrderId, Commands.orderNumber(workOrderId));
    }

    public Order issued() {
        Order order = newOrder();
        commands.handle(Commands.generateBudget(order.sagaId(), order.workOrderId(), "450.00"));
        return order;
    }

    public String approvedBudget(Order order) {
        return budgets.decideAsStaff(order.orderNumber(), Decision.APPROVE).budgetId();
    }

    /** @return id local do pagamento */
    public String pendingPix(Order order) {
        String budgetId = approvedBudget(order);
        commands.handle(Commands.requestPayment(order.sagaId(), order.workOrderId(), budgetId, "450.00", 1));
        return budgets.detailsForStaff(order.orderNumber()).payments().get(0).paymentId();
    }

    public String paidPix(Order order) {
        String paymentId = pendingPix(order);
        String providerId = providerId(paymentId);
        gateway.simulate(providerId, ProviderStatus.APPROVED, "accredited");
        sync.sync(providerId, NoticeSource.WEBHOOK);
        return paymentId;
    }

    public String providerId(String paymentId) {
        return sync.providerPaymentId(UUID.fromString(paymentId)).orElseThrow();
    }

    /** Código de autorização tirado do e-mail enviado ao cliente (o único lugar onde ele existe em claro). */
    public String emailedToken(Order order) {
        List<Mail> mails = mailbox.getMailsSentTo("maria@exemplo.com");
        for (int i = mails.size() - 1; i >= 0; i--) {
            Mail mail = mails.get(i);
            if (mail.getSubject().contains(order.orderNumber())) {
                Matcher matcher = TOKEN.matcher(mail.getText());
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        throw new IllegalStateException("Nenhum e-mail de orçamento para " + order.orderNumber());
    }

    public record Order(UUID sagaId, long workOrderId, String orderNumber) {
    }
}
