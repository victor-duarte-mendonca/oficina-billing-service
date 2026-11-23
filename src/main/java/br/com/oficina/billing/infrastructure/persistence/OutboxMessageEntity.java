package br.com.oficina.billing.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Tabela {@code outbox}. O {@code id} é o {@code messageId} — chave de deduplicação no SQS FIFO. */
@Entity
@Table(name = "outbox")
public class OutboxMessageEntity {

    private static final int MAX_ERROR = 500;

    @Id
    private UUID id;

    @Column(name = "message_group_id", nullable = false, updatable = false)
    private String messageGroupId;

    @Column(name = "message_type", nullable = false, updatable = false)
    private String messageType;

    /** Envelope completo, já serializado: o relay publica exatamente o que foi gravado na transação. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxMessageEntity() {
    }

    public OutboxMessageEntity(UUID id, String messageGroupId, String messageType, String payload,
                               OffsetDateTime createdAt) {
        this.id = id;
        this.messageGroupId = messageGroupId;
        this.messageType = messageType;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public void markPublished(OffsetDateTime when) {
        publishedAt = when;
        attempts++;
        lastError = null;
    }

    public void markFailed(String error) {
        attempts++;
        lastError = error == null || error.length() <= MAX_ERROR ? error : error.substring(0, MAX_ERROR);
    }

    public UUID getId() { return id; }
    public String getMessageGroupId() { return messageGroupId; }
    public String getMessageType() { return messageType; }
    public String getPayload() { return payload; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
}
