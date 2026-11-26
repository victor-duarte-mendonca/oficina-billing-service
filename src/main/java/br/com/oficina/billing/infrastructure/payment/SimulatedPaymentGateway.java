package br.com.oficina.billing.infrastructure.payment;

import br.com.oficina.billing.application.ports.out.PaymentGatewayPort;
import br.com.oficina.billing.domain.model.PixCharge;
import br.com.oficina.billing.domain.model.ProviderStatus;
import br.com.oficina.billing.domain.valueobject.Money;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provedor simulado em memória, com o mesmo comportamento observável do Mercado Pago: idempotência por chave, Pix
 * pendente até alguém "pagar" ({@link #simulate}), estorno só de pagamento aprovado. Serve aos testes (determinístico)
 * e a dev/hml, onde a rota {@code /admin/payments/{id}/simulate} substitui o app do banco (spec 05 §4, V4).
 * Nunca é usado com {@code app.payment.provider=stripe}.
 */
@ApplicationScoped
@Typed(SimulatedPaymentGateway.class)
public class SimulatedPaymentGateway implements PaymentGatewayPort {

    private final Map<String, Charge> charges = new ConcurrentHashMap<>();
    private final Map<UUID, String> chargeByKey = new ConcurrentHashMap<>();
    private final Set<UUID> refundKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, RefundOutcome> refunds = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1_000);
    private final AtomicInteger creationsToFail = new AtomicInteger();
    private volatile String refundRefusal;
    private volatile boolean unavailable;
    private volatile boolean holdRefunds;

    @Override
    public PixCharge createPix(PixRequest request) {
        if (unavailable || creationsToFail.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
            throw new PaymentGatewayException("provedor simulado indisponível", true);
        }
        String id = chargeByKey.computeIfAbsent(request.idempotencyKey(), key -> {
            String created = "sim-" + sequence.incrementAndGet();
            PixCharge pix = new PixCharge(created, "00020126580014br.gov.bcb.pix0136" + created + "5204000053039865802BR",
                "iVBORw0KGgo=", "https://pix.simulado.local/" + created, request.expiresAt());
            charges.put(created, new Charge(pix, request.amount(), ProviderStatus.PENDING, null, false));
            return created;
        });
        return charges.get(id).pix();
    }

    @Override
    public ProviderPayment find(String providerPaymentId) {
        if (unavailable) {
            throw new PaymentGatewayException("provedor simulado indisponível", true);
        }
        Charge charge = charge(providerPaymentId);
        return new ProviderPayment(providerPaymentId, charge.status(), charge.detail());
    }

    @Override
    public RefundOutcome refund(String providerPaymentId, Money amount, UUID idempotencyKey) {
        if (unavailable) {
            throw new PaymentGatewayException("provedor simulado indisponível", true);
        }
        if (refundRefusal != null) {
            throw new PaymentGatewayException(refundRefusal, false);
        }
        if (!refundKeys.add(idempotencyKey)) {
            return refunds.getOrDefault(providerPaymentId, RefundOutcome.SUCCEEDED);
        }
        Charge charge = charge(providerPaymentId);
        if (charge.status() != ProviderStatus.APPROVED || charge.refunded()) {
            refundKeys.remove(idempotencyKey);
            throw new PaymentGatewayException("estorno só de pagamento aprovado e não estornado", false);
        }
        if (amount.compareTo(charge.amount()) != 0) {
            refundKeys.remove(idempotencyKey);
            throw new PaymentGatewayException("valor do estorno difere do pagamento", false);
        }
        charges.put(providerPaymentId, charge.withRefund());
        RefundOutcome outcome = holdRefunds ? RefundOutcome.PENDING : RefundOutcome.SUCCEEDED;
        refunds.put(providerPaymentId, outcome);
        return outcome;
    }

    @Override
    public Optional<RefundOutcome> findRefund(String providerPaymentId) {
        if (unavailable) {
            throw new PaymentGatewayException("provedor simulado indisponível", true);
        }
        return Optional.ofNullable(refunds.get(providerPaymentId));
    }

    /** Próximos estornos ficam pendentes no provedor (como o Pix real nos primeiros segundos). */
    public void holdRefunds(boolean hold) {
        holdRefunds = hold;
    }

    /** O provedor conclui (ou falha) o estorno pendente; o Billing só fica sabendo por webhook ou reconciliação. */
    public void settleRefund(String providerPaymentId, RefundOutcome outcome) {
        refunds.put(providerPaymentId, outcome);
    }

    /** O cliente "pagou" (ou o banco recusou, ou o Pix venceu). */
    public void simulate(String providerPaymentId, ProviderStatus status, String detail) {
        Charge charge = charge(providerPaymentId);
        charges.put(providerPaymentId, new Charge(charge.pix(), charge.amount(), status, detail, charge.refunded()));
    }

    public boolean isRefunded(String providerPaymentId) {
        return Optional.ofNullable(charges.get(providerPaymentId)).map(Charge::refunded).orElse(false);
    }

    /** Próximas {@code count} criações falham como indisponibilidade (teste de {@code PaymentRequestFailed}). */
    public void failNextCreations(int count) {
        creationsToFail.set(count);
    }

    /** Estornos recusados com o motivo dado; {@code null} volta ao normal. */
    public void refuseRefunds(String reason) {
        refundRefusal = reason;
    }

    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    public void reset() {
        creationsToFail.set(0);
        holdRefunds = false;
        refundRefusal = null;
        unavailable = false;
    }

    private Charge charge(String providerPaymentId) {
        Charge charge = charges.get(providerPaymentId);
        if (charge == null) {
            throw new PaymentGatewayException("pagamento desconhecido no provedor: " + providerPaymentId, false);
        }
        return charge;
    }

    private record Charge(PixCharge pix, Money amount, ProviderStatus status, String detail, boolean refunded) {

        Charge withRefund() {
            return new Charge(pix, amount, status, detail, true);
        }
    }
}
