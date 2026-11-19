-- ============================================================
-- POS PLATFORM - V19 fiscal tramo 2 (fase 11 · ADR-021)
--
-- Three deliverables, zero new RLS tables (withholding_rate is global
-- reference data like tax_rate — NOT tenant-scoped, RLS count stays 26):
--
-- 1. IEPS->IVA chain (ADR-018 extension): a line can now carry TWO
--    traslados — IEPS on the net and IVA on the IEPS-inclusive base.
--    sale_line/invoice_line/sale_return_line keep tax_total as the SUM
--    of ALL traslados (legacy rows: ieps_total 0 -> tax_total IS the
--    IVA; an old variant whose single code was IEPS keeps stamping
--    correctly because the scheme comes from the catalog row) and gain
--    ieps_rate_code/ieps_total for the IEPS part; IVA amount is always
--    tax_total - ieps_total.
-- 2. Retenciones (document level): federal catalog + a jsonb SNAPSHOT
--    on the invoice (same law as ADR-018: the retention is captured
--    once, never recomputed). Total formula gains a term:
--    total = subtotal - descuento + traslados - retenciones.
-- 3. Complemento de pago (kind PAYMENT): CFDI tipo P carrying the
--    settled amount against a STAMPED invoice (link via the existing
--    related_invoice_id column).
-- ============================================================

-- ---- 1) IEPS->IVA chain columns ----
ALTER TABLE product_variant   ADD COLUMN ieps_rate_code varchar(16);
ALTER TABLE sale_line         ADD COLUMN ieps_rate_code varchar(16);
ALTER TABLE sale_line         ADD COLUMN ieps_total     numeric(14, 2) NOT NULL DEFAULT 0;
ALTER TABLE invoice_line      ADD COLUMN ieps_rate_code varchar(16);
ALTER TABLE invoice_line      ADD COLUMN ieps_total     numeric(14, 2) NOT NULL DEFAULT 0;
ALTER TABLE sale_return_line  ADD COLUMN ieps_rate_code varchar(16);
ALTER TABLE sale_return_line  ADD COLUMN ieps_total     numeric(14, 2) NOT NULL DEFAULT 0;

-- ---- 2) Retenciones: federal catalog (global reference data) ----
CREATE TABLE withholding_rate (
    id          uuid PRIMARY KEY,
    code        varchar(16) NOT NULL UNIQUE,
    scheme      varchar(8)  NOT NULL,          -- ISR | IVA
    rate        numeric(10, 6) NOT NULL,       -- FRACTION, same law as tax_rate
    kind        varchar(8)  NOT NULL DEFAULT 'TASA',
    description text
);

INSERT INTO withholding_rate (id, code, scheme, rate, kind, description) VALUES
    (gen_random_uuid(), 'RETISR10',  'ISR', 0.100000, 'TASA',
     'Retención ISR 10% (arrendamiento y enajenación de bienes)'),
    (gen_random_uuid(), 'RETISR125', 'ISR', 0.012500, 'TASA',
     'Retención ISR 1.25% (servicios profesionales)'),
    (gen_random_uuid(), 'RETIVA23',  'IVA', 0.106667, 'TASA',
     'Retención IVA 2/3 (10.6667% sobre la base del IVA 16%)');

-- ---- 2) Retenciones snapshot on the invoice (document level) ----
ALTER TABLE invoice ADD COLUMN withholding        jsonb;
ALTER TABLE invoice ADD COLUMN withholding_total  numeric(14, 2) NOT NULL DEFAULT 0;

-- ---- 3) Complemento de pago (CFDI tipo P, kind PAYMENT) ----
ALTER TABLE invoice DROP CONSTRAINT invoice_kind_check;
ALTER TABLE invoice ADD CONSTRAINT invoice_kind_check
CHECK (kind IN ('INDIVIDUAL', 'GLOBAL', 'CREDIT_NOTE', 'PAYMENT'));

ALTER TABLE invoice ADD COLUMN payment_amount numeric(14, 2);
ALTER TABLE invoice ADD COLUMN payment_method varchar(4);
ALTER TABLE invoice ADD COLUMN payment_date   timestamptz;

-- ---- Privilege sweep (withholding_rate is global reference: SELECT only;
--      the ALTERed tables keep their existing grants) ----
GRANT SELECT ON withholding_rate TO posplatform_app;
