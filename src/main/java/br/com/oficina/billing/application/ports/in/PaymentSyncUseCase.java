package br.com.oficina.billing.application.ports.in;

import br.com.oficina.billing.application.ports.out.PaymentRepository.NoticeSource;
import java.util.Optional;
import java.util.UUID;

/**
 * Atualiza um pagamento a partir do provedor. Usado pelo webhook (com o {@code data.id} da notificação) e pela
 * reconciliação; em ambos a situação vem de uma <b>consulta</b> ao provedor, nunca do corpo recebido.
 */
public interface PaymentSyncUseCase {

    SyncResult sync(String providerPaymentId, NoticeSource source);

    /** Identificador do pagamento no provedor (rota de simulação de dev/hml). */
    Optional<String> providerPaymentId(UUID paymentId);

    enum SyncResult {
        /** O pagamento mudou e a resposta foi para o outbox. */
        UPDATED,
        /** Nada novo (notificação repetida, ou ainda pendente). */
        UNCHANGED,
        /** Não é um pagamento deste serviço: responde 200 e ignora, para o provedor não reenviar para sempre. */
        UNKNOWN
    }
}
