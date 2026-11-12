-- ============================================================
-- POS PLATFORM - V6 outbox & idempotency
--
-- outbox_event    : Transactional Outbox (events written in the SAME tx as
--                   the business change; relay comes later - ADR-002/ADR-005)
-- idempotency_key : future offline/sync infrastructure (schema in place)
-- ============================================================

CREATE TABLE outbox_event (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      uuid,
    event_type     varchar(120) NOT NULL,
    aggregate_type varchar(120) NOT NULL,
    aggregate_id   uuid,
    payload        jsonb NOT NULL,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    processed_at   timestamptz
);

CREATE INDEX idx_outbox_pending ON outbox_event (id) WHERE processed_at IS NULL;
CREATE INDEX idx_outbox_tenant ON outbox_event (tenant_id);

CREATE TABLE idempotency_key (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL,
    key        varchar(120) NOT NULL,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, key)
);
