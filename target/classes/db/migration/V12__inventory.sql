-- ============================================================
-- POS PLATFORM - V12 inventory core (ADR-004 + ADR-010)
--
-- warehouse          : stock location (usually one per branch). UNIQUE
--                      (tenant_id, code) — the same code never repeats
--                      inside a tenant. Composite FK ids for same-tenant.
-- inventory_movement : IMMUTABLE history (every entry has direction, cause,
--                      quantity and optional lot). Corrections are
--                      COMPENSATORY movements linked through
--                      related_movement_id — never UPDATE/DELETE.
-- inventory_lot      : lot + expiry level of stock (used when the tenant's
--                      vertical tracks LOT/EXPIRY, ADR-010).
-- inventory_balance  : aggregate quantity per (warehouse, variant), updated
--                      in the SAME transaction as the movement. No positive
--                      CHECK on quantity: negative stock is a POLICY of the
--                      vertical (FORBIDDEN / ALLOWED_WITH_ALERT / ALLOWED),
--                      enforced by the service.
-- RLS: tenant_isolation exactly like V7/V10/V11 (same GUC contract).
-- ============================================================

-- Composite-FK prerequisite: inventory tables reference
-- product_variant (id, tenant_id), which V10 never declared unique.
-- The id PK is already globally unique, so this index adds no new
-- restriction — it only lets the same-tenant FK match.
CREATE UNIQUE INDEX uq_product_variant_id_tenant
    ON product_variant (id, tenant_id);

CREATE TABLE warehouse (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES organization (id),
    branch_id  uuid,
    code       varchar(32) NOT NULL,
    name       varchar(120) NOT NULL,
    active     boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, code)
);

CREATE INDEX idx_warehouse_tenant ON warehouse (tenant_id);

CREATE TABLE inventory_movement (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid NOT NULL REFERENCES organization (id),
    warehouse_id        uuid NOT NULL,
    variant_id          uuid NOT NULL,
    direction           text NOT NULL CHECK (direction IN ('IN', 'OUT')),
    cause               text NOT NULL CHECK (cause IN
                        ('PURCHASE', 'ADJUST', 'SALE', 'SALE_RETURN',
                         'TRANSFER_IN', 'TRANSFER_OUT')),
    quantity            numeric(14, 4) NOT NULL CHECK (quantity > 0),
    lot_code            varchar(64),
    expiry_date         date,
    reference           varchar(120),
    note                varchar(280),
    related_movement_id uuid,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouse (id, tenant_id),
    FOREIGN KEY (variant_id, tenant_id) REFERENCES product_variant (id, tenant_id),
    FOREIGN KEY (related_movement_id, tenant_id)
        REFERENCES inventory_movement (id, tenant_id)
);

CREATE INDEX idx_movement_tenant_created  ON inventory_movement (tenant_id, created_at DESC);
CREATE INDEX idx_movement_tenant_variant  ON inventory_movement (tenant_id, variant_id);
CREATE INDEX idx_movement_tenant_source   ON inventory_movement (tenant_id, reference, cause);

CREATE TABLE inventory_lot (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES organization (id),
    warehouse_id uuid NOT NULL,
    variant_id  uuid NOT NULL,
    lot_code    varchar(64) NOT NULL,
    expiry_date date,
    quantity    numeric(14, 4) NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, warehouse_id, variant_id, lot_code),
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouse (id, tenant_id),
    FOREIGN KEY (variant_id, tenant_id) REFERENCES product_variant (id, tenant_id)
);

CREATE INDEX idx_lot_tenant_variant ON inventory_lot (tenant_id, variant_id);

CREATE TABLE inventory_balance (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES organization (id),
    warehouse_id uuid NOT NULL,
    variant_id   uuid NOT NULL,
    quantity     numeric(14, 4) NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, warehouse_id, variant_id),
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouse (id, tenant_id),
    FOREIGN KEY (variant_id, tenant_id) REFERENCES product_variant (id, tenant_id)
);

CREATE INDEX idx_balance_tenant ON inventory_balance (tenant_id);

-- ---- RLS (same contract as V7/V10/V11) ----
ALTER TABLE warehouse           ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_movement  ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_lot       ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_balance   ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON warehouse
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON inventory_movement
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON inventory_lot
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON inventory_balance
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Privilege sweep (belt & suspenders over the V1 ALTER DEFAULT PRIVILEGES).
GRANT SELECT, INSERT, UPDATE, DELETE
    ON warehouse, inventory_movement, inventory_lot, inventory_balance
    TO posplatform_app;
