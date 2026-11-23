package br.com.oficina.billing.infrastructure.persistence;

import br.com.oficina.billing.application.ports.out.InboxPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import java.util.UUID;

/**
 * Inbox no PostgreSQL. {@code ON CONFLICT DO NOTHING} decide a duplicidade no banco, de forma atômica: duas réplicas
 * recebendo a mesma mensagem ao mesmo tempo, só uma insere.
 */
@ApplicationScoped
public class InboxRepositoryAdapter implements InboxPort {

    private final EntityManager em;

    public InboxRepositoryAdapter(EntityManager em) {
        this.em = em;
    }

    @Override
    public boolean markProcessed(UUID messageId) {
        return em.createNativeQuery("INSERT INTO inbox (message_id) VALUES (:id) ON CONFLICT DO NOTHING")
            .setParameter("id", messageId)
            .executeUpdate() == 1;
    }

    @Override
    public boolean wasProcessed(UUID messageId) {
        return !em.createNativeQuery("SELECT 1 FROM inbox WHERE message_id = :id")
            .setParameter("id", messageId)
            .getResultList().isEmpty();
    }
}
