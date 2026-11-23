package br.com.oficina.billing.infrastructure.notification;

import br.com.oficina.billing.application.ports.out.BudgetNotifierPort;
import br.com.oficina.billing.domain.model.Budget;
import br.com.oficina.billing.domain.model.BudgetLine;
import br.com.oficina.billing.domain.valueobject.ApprovalToken;
import br.com.oficina.billing.infrastructure.configuration.BillingConfig;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * Envia o orçamento por e-mail com o código de autorização. O token vai no corpo, nunca numa URL (não aparece em log
 * de acesso nem em histórico de navegador). Falha de SMTP é logada sem o token e não desfaz o orçamento.
 */
@ApplicationScoped
public class BudgetEmailNotifier implements BudgetNotifierPort {

    private static final Logger LOG = Logger.getLogger(BudgetEmailNotifier.class);
    private static final DateTimeFormatter DEADLINE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        .withZone(ZoneId.of("America/Sao_Paulo"));

    private final Mailer mailer;
    private final String approvalUrl;

    public BudgetEmailNotifier(Mailer mailer, BillingConfig config) {
        this.mailer = mailer;
        this.approvalUrl = config.budget().publicApprovalUrl();
    }

    @Override
    public void budgetIssued(Budget budget, ApprovalToken token) {
        String orderNumber = budget.ref().orderNumber();
        if (budget.customer().optionalEmail().isEmpty()) {
            LOG.warnf("OS %s sem e-mail do cliente: orçamento disponível só pela área do cliente ou pelo atendimento",
                orderNumber);
            return;
        }
        try {
            mailer.send(Mail.withText(budget.customer().email(), "Orçamento da OS %s".formatted(orderNumber),
                body(budget, token)));
            LOG.infof("Orçamento da OS %s enviado ao cliente", orderNumber);
        } catch (RuntimeException e) {
            LOG.errorf("Falha ao enviar o orçamento da OS %s: %s", orderNumber, e.getClass().getSimpleName());
        }
    }

    private String body(Budget budget, ApprovalToken token) {
        String items = budget.lines().stream().map(BudgetEmailNotifier::line).collect(Collectors.joining("\n"));
        return """
            Olá, %s!

            O orçamento da Ordem de Serviço %s está pronto:

            %s

            Total: R$ %s
            Válido até: %s

            Para aprovar ou recusar, use o código de autorização abaixo em
            %s/%s/approve (ou /reject), ou acesse a sua área de cliente.

            Código de autorização: %s

            O código é pessoal e vale para uma única decisão.

            Oficina Mecânica
            """.formatted(budget.customer().name(), budget.ref().orderNumber(), items, budget.total().toWire(),
            DEADLINE.format(budget.expiresAt()), approvalUrl, budget.ref().orderNumber(), token.value());
    }

    private static String line(BudgetLine line) {
        return "- %s x%d: R$ %s".formatted(line.name(), line.quantity(), line.subtotal().toWire());
    }
}
