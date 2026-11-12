-- ============================================================
-- POS PLATFORM - V10 catalog products (ADR-004/010, fase 3 start)
--
-- product         : commercial definition of the tenant; base_unit MUST be
--                   one of the tenant's vertical profile units (code guard).
-- product_variant : SKU merchandise item; SKU is unique PER TENANT (partial
--                   unique index mirrors the service rule); attributes carry
--                   ONLY the vertical's declared product-attribute keys.
-- price           : NUMERIC(14,4) — never float (money rule).
--
-- RLS: tenant_isolation policies exactly like V7 (same GUC contract).
-- ============================================================

CREATE TABLE product (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES organization (id),
    name        varchar(180) NOT NULL,
    description varchar(500),
    category    varchar(120),
    base_unit   varchar(8)  NOT NULL,
    status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISCONTINUED')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_product_tenant ON product (tenant_id);
CREATE INDEX idx_product_tenant_name ON product (tenant_id, name);
-- unique (id, tenant_id) so product_variant can composite-FK into product
CREATE UNIQUE INDEX uq_product_id_tenant ON product (id, tenant_id);

CREATE TABLE product_variant (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES organization (id),
    product_id  uuid NOT NULL,
    sku         varchar(64) NOT NULL,
    barcode     varchar(64),
    unit        varchar(8) NOT NULL,
    attributes  jsonb NOT NULL DEFAULT '{}'::jsonb,
    price       numeric(14, 4),
    status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISCONTINUED')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (product_id, tenant_id) REFERENCES product (id, tenant_id)
);

-- SKU unique per tenant (defense in depth over the service check)
CREATE UNIQUE INDEX uq_product_variant_tenant_sku ON product_variant (tenant_id, sku);
CREATE INDEX idx_product_variant_tenant ON product_variant (tenant_id);
CREATE INDEX idx_product_variant_product ON product_variant (product_id);

-- ---- RLS (same contract as V7) ----
ALTER TABLE product ENABLE ROW LEVEL SECURITY;
ALTER TABLE product_variant ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON product
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON product_variant
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Privilege sweep (covers the new tables immediately, belt & suspenders over
-- the V1 ALTER DEFAULT PRIVILEGES).
GRANT SELECT, INSERT, UPDATE, DELETE ON product, product_variant TO posplatform_app;
