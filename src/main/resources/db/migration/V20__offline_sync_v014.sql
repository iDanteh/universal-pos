-- ============================================================
-- POS PLATFORM - V20 offline-first sync (fase 14 · ADR-024)
--
-- The POS desktop does NOT replicate tables: it executes INTENTS and queues
-- them locally. This migration adds the server-side ledger of those intents:
--
--   pos_device      a registered till (code unique per tenant)
--   sync_batch      one delivery from a device, replay-safe by clientBatchId
--   sync_operation  one intent, UNIQUE per tenant: re-sending it returns the
--                   SAME result and never duplicates the sale (idempotency).
--
-- No catalog/price snapshots are stored here: the sale operation reuses the
-- sales module, which already snapshots prices/taxes in sale/sale_line.
-- New permission `sync.operate` migrates GRANTED to system roles (rule #7).
-- ============================================================

INSERT INTO permission (id, code, name, description) VALUES
    (gen_random_uuid(), 'sync.operate', 'Sincronizar terminales POS offline', NULL);

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
JOIN permission p ON p.code = 'sync.operate'
WHERE r.is_system = true
ON CONFLICT (role_id, permission_id) DO NOTHING;

CREATE TABLE pos_device (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES organization (id),
    code          varchar(60) NOT NULL,
    name          varchar(120) NOT NULL,
    registered_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, code)
);

CREATE TABLE sync_batch (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES organization (id),
    device_id       uuid NOT NULL,
    user_id         uuid NOT NULL REFERENCES app_user (id),
    client_batch_id uuid NOT NULL,
    status          text NOT NULL
                    CHECK (status IN ('RECEIVED', 'APPLIED', 'PARTIAL', 'REJECTED')),
    operation_count int NOT NULL CHECK (operation_count BETWEEN 1 AND 50),
    applied_count   int NOT NULL DEFAULT 0,
    rejected_count  int NOT NULL DEFAULT 0,
    duplicate_count int NOT NULL DEFAULT 0,
    received_at     timestamptz NOT NULL DEFAULT now(),
    acked_at        timestamptz,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, client_batch_id),
    FOREIGN KEY (device_id, tenant_id) REFERENCES pos_device (id, tenant_id)
);

CREATE INDEX idx_sync_batch_tenant_received ON sync_batch (tenant_id, received_at DESC);
CREATE INDEX idx_sync_batch_device          ON sync_batch (tenant_id, device_id);

CREATE TABLE sync_operation (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES organization (id),
    batch_id     uuid NOT NULL,
    operation_id uuid NOT NULL,
    type         text NOT NULL CHECK (type IN ('SALE')),
    payload      jsonb NOT NULL,
    status       text NOT NULL CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED')),
    error_code   text,
    error_detail text,
    result       jsonb,
    occurred_at  timestamptz,
    applied_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    -- The intent identity: a re-sent operation can NEVER create a second row
    -- (and the sale inside reuses `sync:<operationId>` as idempotency key).
    UNIQUE (tenant_id, operation_id),
    FOREIGN KEY (batch_id, tenant_id) REFERENCES sync_batch (id, tenant_id)
);

CREATE INDEX idx_sync_operation_batch ON sync_operation (tenant_id, batch_id);

-- ---- RLS (same contract as V7/V10-V19) ----
ALTER TABLE pos_device      ENABLE ROW LEVEL SECURITY;
ALTER TABLE sync_batch      ENABLE ROW LEVEL SECURITY;
ALTER TABLE sync_operation  ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON pos_device
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON sync_batch
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON sync_operation
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- ---- Privilege sweep ----
GRANT SELECT, INSERT, UPDATE, DELETE
    ON pos_device, sync_batch, sync_operation
    TO posplatform_app;
