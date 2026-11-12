-- ============================================================
-- POS PLATFORM - V11 sales core (ADR-004/006 + sales-payments.md)
--
-- sale_folio     : per-tenant folio counter — `INSERT ... ON CONFLICT DO
--                  UPDATE ... RETURNING last` inside the sale transaction is
--                  the atomic folio source (gaps appear only on rollback).
-- sale           : completed/canceled commercial operation. Money is
--                  NUMERIC(14,2) — NEVER float; totals are computed once and
--                  stored (history never recalculates).
-- sale_line      : SNAPSHOT line: product name/sku/unit/unit_price are copied
--                  at sale time; later catalog changes never rewrite history.
-- sale_payment   : method + amount + reference; payments MUST sum to total.
-- idempotency_key: gains resource columns — the V6 unique (tenant_id, key)
--                  is the replay guard for POST /api/v1/sales.
--
-- Same-tenant integrity: composite FKs (sale_id, tenant_id) -> sale(id, tenant_id)
-- (same pattern as V10 product_variant). RLS: tenant_isolation same contract.
-- ============================================================

CREATE TABLE sale_folio (
    tenant_id uuid PRIMARY KEY REFERENCES organization (id),
    last      bigint NOT NULL DEFAULT 0
);

CREATE TABLE sale (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES organization (id),
    folio           bigint NOT NULL,
    status          text NOT NULL DEFAULT 'COMPLETED'
                    CHECK (status IN ('COMPLETED', 'CANCELED')),
    status_reason   text,
    note            varchar(280),
    currency        text NOT NULL DEFAULT 'MXN',
    subtotal        numeric(14, 2) NOT NULL,
    discount_total  numeric(14, 2) NOT NULL DEFAULT 0,
    tax_total       numeric(14, 2) NOT NULL DEFAULT 0,
    total           numeric(14, 2) NOT NULL,
    idempotency_key varchar(120) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, folio),
    UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_sale_tenant_created ON sale (tenant_id, created_at DESC);
CREATE INDEX idx_sale_tenant_status  ON sale (tenant_id, status);

CREATE TABLE sale_line (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES organization (id),
    sale_id      uuid NOT NULL,
    variant_id   uuid NOT NULL,
    product_name varchar(180) NOT NULL,
    sku          varchar(64) NOT NULL,
    unit         varchar(8) NOT NULL,
    quantity     numeric(14, 4) NOT NULL CHECK (quantity > 0),
    unit_price   numeric(14, 4) NOT NULL CHECK (unit_price >= 0),
    discount     numeric(14, 4) NOT NULL DEFAULT 0 CHECK (discount >= 0),
    line_total   numeric(14, 2) NOT NULL CHECK (line_total >= 0),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (sale_id, tenant_id) REFERENCES sale (id, tenant_id),
    CHECK (quantity * unit_price - discount >= 0)
);

CREATE INDEX idx_sale_line_tenant ON sale_line (tenant_id);
CREATE INDEX idx_sale_line_sale   ON sale_line (sale_id);

CREATE TABLE sale_payment (
    id        uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES organization (id),
    sale_id   uuid NOT NULL,
    method    text NOT NULL CHECK (method IN ('CASH', 'CARD', 'TRANSFER', 'OTHER')),
    amount    numeric(14, 2) NOT NULL CHECK (amount > 0),
    reference varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (sale_id, tenant_id) REFERENCES sale (id, tenant_id)
);

CREATE INDEX idx_sale_payment_tenant ON sale_payment (tenant_id);
CREATE INDEX idx_sale_payment_sale   ON sale_payment (sale_id);

-- Idempotency reuse for sales (V6 kept the unique (tenant_id, key) guard).
ALTER TABLE idempotency_key ADD COLUMN resource_type text;
ALTER TABLE idempotency_key ADD COLUMN resource_id uuid;
CREATE INDEX idx_idempotency_resource ON idempotency_key (resource_type, resource_id);

-- ---- RLS (same contract as V7/V10) ----
ALTER TABLE sale_folio   ENABLE ROW LEVEL SECURITY;
ALTER TABLE sale         ENABLE ROW LEVEL SECURITY;
ALTER TABLE sale_line    ENABLE ROW LEVEL SECURITY;
ALTER TABLE sale_payment ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON sale_folio
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON sale
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON sale_line
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON sale_payment
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Privilege sweep (belt & suspenders over the V1 ALTER DEFAULT PRIVILEGES).
GRANT SELECT, INSERT, UPDATE, DELETE
    ON sale_folio, sale, sale_line, sale_payment, idempotency_key
    TO posplatform_app;
