package br.com.oficina.billing.interfaces.rest;

import br.com.oficina.billing.application.ports.out.ConcurrentUpdateException;
import br.com.oficina.billing.application.ports.out.PaymentGatewayPort.PaymentGatewayException;
import br.com.oficina.billing.domain.exception.BillingRuleViolation;
import br.com.oficina.billing.domain.exception.BudgetNotFoundException;
import br.com.oficina.billing.domain.exception.PaymentNotFoundException;
import br.com.oficina.billing.infrastructure.observability.RequestContext;
import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;

/** Erros em JSON, sem stacktrace nem detalhe interno para o cliente; o inesperado é logado com a correlação. */
@Provider
public class ErrorMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(ErrorMapper.class);
    private static final int UNPROCESSABLE_ENTITY = 422;
    private static final String CONFLICT = "O orçamento foi alterado ao mesmo tempo por outra pessoa. Recarregue e tente de novo.";

    @Override
    public Response toResponse(Throwable exception) {
        return switch (exception) {
            case BudgetNotFoundException e -> error(Response.Status.NOT_FOUND.getStatusCode(), "Orçamento não encontrado");
            case PaymentNotFoundException e -> error(Response.Status.NOT_FOUND.getStatusCode(), e.getMessage());
            case BillingRuleViolation e -> error(UNPROCESSABLE_ENTITY, e.getMessage());
            case ConcurrentUpdateException e -> error(Response.Status.CONFLICT.getStatusCode(), CONFLICT);
            case OptimisticLockException e -> error(Response.Status.CONFLICT.getStatusCode(), CONFLICT);
            case PaymentGatewayException e -> {
                LOG.warnf("Provedor de pagamento indisponível (correlationId=%s): %s",
                    RequestContext.correlationId().orElse("-"), e.getMessage());
                yield error(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(),
                    "Provedor de pagamento indisponível. Tente novamente em instantes.");
            }
            case WebApplicationException e -> e.getResponse();
            default -> {
                LOG.errorf(exception, "Erro inesperado (correlationId=%s)", RequestContext.correlationId().orElse("-"));
                yield error(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                    "Erro interno. Informe o correlationId ao suporte.");
            }
        };
    }

    private static Response error(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status);
        body.put("message", message);
        RequestContext.correlationId().ifPresent(id -> body.put("correlationId", id));
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body).build();
    }
}
