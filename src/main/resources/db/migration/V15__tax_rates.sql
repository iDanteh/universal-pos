-- ============================================================
-- POS PLATFORM - V15 tax domain núcleo (ADR-006 + ADR-018)
--
-- tax_rate      : GLOBAL (not tenant-scoped, no RLS) versioned catalog of
--                 Mexican transferred taxes. Rates belong to the LAW, not to
--                 a tenant (ADR-006 rule 4: catalogs live in MIGRATIONS, never
--                 hardcoded in logic). New rates (e.g. a 2031 IVA change, a
--                 new IEPS cuota) arrive as new migrations, never UPDATEs.
-- product_variant: gains tax_rate_code — the tenant ASSIGNS the rate per
--                 SKU in the catalog (0..1 traslado per line in v0.8; the
--                 IEPS->IVA chain multi-tax combo arrives later).
-- sale_line     : gains tax_rate_code + tax_total — the tax is computed ONCE
--                 on the line net (qty x price - discount, exactly the CFDI
--                 4.0 "Base" concept) and SNAPSHOTTED (history never
--                 recalculates, same rule as prices).
-- invoice_line  : mirrors the sale line tax 1:1 (the CFDI never re-bills a
--                 different amount nor rate than what was charged).
--
-- Integrity: variant/sale/invoice codes are validated service-side against
-- tax_rate (same pattern as sat_catalog). RLS: tax_rate is public reference
-- data (SELECT-only for the app). DEFAULT 0 on tax_total columns keeps every
-- legacy row and behavior EXACTLY as before (pre-tax sales stay coherent).
-- ============================================================

CREATE TABLE tax_rate (
    id          uuid PRIMARY KEY,
    code        varchar(16) NOT NULL UNIQUE,
    scheme      text NOT NULL CHECK (scheme IN ('IVA', 'IEPS')),
    rate        numeric(8, 4) NOT NULL CHECK (rate >= 0 AND rate <= 1),
    factor      text NOT NULL CHECK (factor IN ('TASA', 'EXENTO')),
    description text NOT NULL,
    active      boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

INSERT INTO tax_rate (id, code, scheme, rate, factor, description) VALUES
    (gen_random_uuid(), 'IVA16',  'IVA',  0.1600, 'TASA',  'IVA 16% (traslado general)'),
    (gen_random_uuid(), 'IVA8',   'IVA',  0.0800, 'TASA',  'IVA 8% (región fronteriza norte)'),
    (gen_random_uuid(), 'IVA0',   'IVA',  0.0000, 'TASA',  'IVA 0% (tasa cero)'),
    (gen_random_uuid(), 'EXENTO', 'IVA',  0.0000, 'EXENTO','IVA exento'),
    (gen_random_uuid(), 'IEPS265','IEPS', 0.2650, 'TASA',  'IEPS 26.5% (alimentos y bebidas no básicos)');

-- ---- Impuesto por SKU (0..1 traslado por línea en v0.8) ----
ALTER TABLE product_variant ADD COLUMN tax_rate_code varchar(16);
ALTER TABLE sale_line ADD COLUMN tax_rate_code varchar(16);
ALTER TABLE sale_line ADD COLUMN tax_total numeric(14, 2) NOT NULL DEFAULT 0;
ALTER TABLE invoice_line ADD COLUMN tax_rate_code varchar(16);
ALTER TABLE invoice_line ADD COLUMN tax_total numeric(14, 2) NOT NULL DEFAULT 0;

-- ---- Privilege sweep (tax_rate is global reference data: SELECT only;
--      the ALTERed tables keep their existing grants) ----
GRANT SELECT ON tax_rate TO posplatform_app;
