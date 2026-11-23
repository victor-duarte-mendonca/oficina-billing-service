-- =====================================================================
-- Billing Service — esquema inicial (spec 02 §3, spec 05).
-- Banco próprio do serviço: nenhum outro serviço lê ou escreve aqui.
-- =====================================================================

CREATE TABLE budget (
    id                  UUID PRIMARY KEY,
    work_order_id       BIGINT       NOT NULL,
    order_number        VARCHAR(20)  NOT NULL,
    saga_id             UUID         NOT NULL,
    client_id           BIGINT       NOT NULL,
    customer_name       VARCHAR(150) NOT NULL,
    customer_email      VARCHAR(254),
    total               NUMERIC(12, 2) NOT NULL CHECK (total > 0),
    status              VARCHAR(20)  NOT NULL
        CHECK (status IN ('SENT', 'APPROVED', 'REJECTED', 'EXPIRED', 'VOIDED')),
    -- Só o hash (SHA-256) do token de aprovação; o valor em claro existe apenas no e-mail.
    approval_token_hash CHAR(64)     NOT NULL,
    token_consumed_at   TIMESTAMPTZ,
    decided_by          VARCHAR(20) CHECK (decided_by IN ('CLIENT_TOKEN', 'CLIENT_JWT', 'STAFF')),
    decided_at          TIMESTAMPTZ,
    void_reason         VARCHAR(300),
    expires_at          TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0
);

-- Um orçamento vivo por OS: dois GenerateBudget concorrentes não criam dois orçamentos.
CREATE UNIQUE INDEX ux_budget_active_work_order ON budget (work_order_id) WHERE status IN ('SENT', 'APPROVED');
CREATE INDEX ix_budget_order_number ON budget (order_number, created_at DESC);
CREATE INDEX ix_budget_sent_expiry ON budget (expires_at) WHERE status = 'SENT';

CREATE TABLE budget_line (
    budget_id   UUID         NOT NULL REFERENCES budget (id),
    position    INT          NOT NULL,
    kind        VARCHAR(10)  NOT NULL CHECK (kind IN ('PART', 'SERVICE')),
    ref_id      BIGINT       NOT NULL,
    name        VARCHAR(150) NOT NULL,
    quantity    INT          NOT NULL CHECK (quantity > 0),
    unit_price  NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),
    PRIMARY KEY (budget_id, position)
);

CREATE TABLE payment (
    id               UUID PRIMARY KEY,
    budget_id        UUID           NOT NULL REFERENCES budget (id),
    work_order_id    BIGINT         NOT NULL,
    order_number     VARCHAR(20)    NOT NULL,
    saga_id          UUID           NOT NULL,
    attempt          INT            NOT NULL CHECK (attempt > 0),
    amount           NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    status           VARCHAR(20)    NOT NULL CHECK (status IN ('REQUESTED', 'PENDING', 'APPROVED', 'REJECTED',
        'EXPIRED', 'FAILED', 'REFUND_PENDING', 'REFUNDED', 'REFUND_FAILED')),
    -- Gravada antes de chamar o provedor: a reentrega reusa a mesma chave (sem cobrança dupla).
    idempotency_key  UUID           NOT NULL UNIQUE,
    mp_payment_id    VARCHAR(64) UNIQUE,
    qr_code          TEXT,
    qr_code_base64   TEXT,
    ticket_url       VARCHAR(500),
    expires_at       TIMESTAMPTZ,
    failure_detail   VARCHAR(300),
    refund_attempts  INT            NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,
    version          BIGINT         NOT NULL DEFAULT 0,
    UNIQUE (budget_id, attempt)
);

-- Um pagamento ativo por orçamento (spec 05 §2): a segunda cobrança concorrente é recusada pelo banco.
CREATE UNIQUE INDEX ux_payment_active_budget ON payment (budget_id)
    WHERE status IN ('REQUESTED', 'PENDING', 'APPROVED', 'REFUND_PENDING');
CREATE INDEX ix_payment_pending ON payment (created_at) WHERE status IN ('REQUESTED', 'PENDING');

-- Trilha do que o provedor informou (reconciliação e auditoria). Sem dados do pagador.
CREATE TABLE payment_event (
    id               BIGSERIAL PRIMARY KEY,
    payment_id       UUID        NOT NULL REFERENCES payment (id),
    source           VARCHAR(10) NOT NULL CHECK (source IN ('WEBHOOK', 'POLL', 'API')),
    provider_status  VARCHAR(30) NOT NULL,
    received_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_payment_event_payment ON payment_event (payment_id, received_at);

-- Transactional Outbox: respostas para a fila os-saga-replies, gravadas na transação da mudança.
CREATE TABLE outbox (
    id                UUID PRIMARY KEY,
    message_group_id  VARCHAR(64)  NOT NULL,
    message_type      VARCHAR(50)  NOT NULL,
    payload           JSONB        NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    published_at      TIMESTAMPTZ,
    attempts          INT          NOT NULL DEFAULT 0,
    last_error        VARCHAR(500)
);
CREATE INDEX ix_outbox_pending ON outbox (created_at, id) WHERE published_at IS NULL;

-- Consumidor idempotente: um comando processado uma única vez, mesmo entregue várias.
CREATE TABLE inbox (
    message_id    UUID PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
