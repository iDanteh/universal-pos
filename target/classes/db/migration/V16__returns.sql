-- ============================================================
-- POS PLATFORM - V16 returns & credit notes (fase 9 · ADR-019)
--
-- return_folio     : per-tenant folio counter for returns (same atomic
--                    pattern as sale_folio/invoice_folio).
-- sale_return      : the inverse commercial operation of a sale (money comes
--                    BACK to the customer). Totals are computed ONCE by
--                    prorating the sale line SNAPSHOT (line_total / tax_total)
--                    proportionally to the returned quantity — the refund can
--                    never be an amount different from what left the drawer.
--                    One refund row per return: refund_method + refund_amount
--                    (= total, enforced by CHECK formula, not client input).
-- sale_return_line : snapshot line referencing the ORIGINAL sale_line
--                    (composite FK after adding UNIQUE (id, tenant_id) to
--                    sale_line). Stock comes back via compensatory
--                    SALE_RETURN movements written by InventoryService in the
--                    SAME transaction (ADR-004 rule 1).
-- invoice          : gains CREDIT_NOTE (CFDI tipo E): return_id + the related
--                    income CFDI (related_invoice_id, TipoRelacion 01). Serie
--                    "NC" reuses invoice_folio (PK tenant+serie). The ONE
--                    active invoice per sale guard is re-scoped to INGRESO
--                    documents; ONE active NC per return gets its own partial
--                    unique index.
--
-- RLS: same tenant_isolation contract (26 tables with policy after V16).
-- ============================================================

CREATE TABLE return_folio (
    tenant_id uuid PRIMARY KEY REFERENCES organization (id),
    last      bigint NOT NULL DEFAULT 0
);

CREATE TABLE sale_return (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES organization (id),
    sale_id          uuid NOT NULL,
    folio            bigint NOT NULL,
    status           text NOT NULL DEFAULT 'COMPLETED'
                     CHECK (status IN ('COMPLETED')),
    note             varchar(280),
    currency         text NOT NULL DEFAULT 'MXN',
    -- snapshot of the prorated sale lines: subtotal is the gross refund,
    -- total = subtotal - discount_total + tax_total (ADR-014 formula held)
    subtotal         numeric(14, 2) NOT NULL,
    discount_total   numeric(14, 2) NOT NULL DEFAULT 0,
    tax_total        numeric(14, 2) NOT NULL DEFAULT 0,
    total            numeric(14, 2) NOT NULL,
    -- the refund is ONE row: the money leaving the drawer. Amount is NOT
    -- client input: it IS the return total (refund == what was charged).
    refund_method    text NOT NULL CHECK (refund_method IN ('CASH', 'CARD', 'TRANSFER', 'OTHER')),
    refund_amount    numeric(14, 2) NOT NULL CHECK (refund_amount > 0),
    refund_reference varchar(80),
    -- attach to the currently OPEN shift (same rule as sale, ADR-017): a CASH
    -- refund reduces the drawer, so the close must expect less.
    cash_session_id  uuid,
    idempotency_key  varchar(120) NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, folio),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (sale_id, tenant_id) REFERENCES sale (id, tenant_id),
    FOREIGN KEY (cash_session_id, tenant_id) REFERENCES cash_session (id, tenant_id),
    CHECK (subtotal - discount_total + tax_total = total)
);

CREATE INDEX idx_sale_return_tenant_created ON sale_return (tenant_id, created_at DESC);
CREATE INDEX idx_sale_return_sale           ON sale_return (tenant_id, sale_id);
CREATE INDEX idx_sale_return_session        ON sale_return (tenant_id, cash_session_id);

-- Composite FK target for sale_return_line (id is already the PK; this makes
-- (id, tenant_id) referenceable — same-tenant integrity, fail-closed). It MUST
-- exist BEFORE the sale_return_line FK below is created.
ALTER TABLE sale_line ADD CONSTRAINT uq_sale_line_id_tenant UNIQUE (id, tenant_id);

CREATE TABLE sale_return_line (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES organization (id),
    return_id     uuid NOT NULL,
    -- provenance: the EXACT sale line being returned (same-tenant composite
    -- FK; V16 adds the UNIQUE (id, tenant_id) sale_line needs to be referenced)
    sale_line_id  uuid NOT NULL,
    variant_id    uuid NOT NULL,
    -- SNAPSHOT copied from the sale line at return time
    product_name  varchar(180) NOT NULL,
    sku           varchar(64) NOT NULL,
    unit          varchar(8) NOT NULL,
    quantity      numeric(14, 4) NOT NULL CHECK (quantity > 0),
    unit_price    numeric(14, 4) NOT NULL CHECK (unit_price >= 0),
    discount      numeric(14, 4) NOT NULL DEFAULT 0 CHECK (discount >= 0),
    line_total    numeric(14, 2) NOT NULL CHECK (line_total >= 0),
    tax_rate_code varchar(16),
    tax_total     numeric(14, 2) NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (return_id, tenant_id) REFERENCES sale_return (id, tenant_id),
    FOREIGN KEY (sale_line_id, tenant_id) REFERENCES sale_line (id, tenant_id)
);

CREATE INDEX idx_sale_return_line_tenant    ON sale_return_line (tenant_id);
CREATE INDEX idx_sale_return_line_return    ON sale_return_line (return_id);
CREATE INDEX idx_sale_return_line_sale_line ON sale_return_line (tenant_id, sale_line_id);

-- ---- invoice gains the CREDIT_NOTE kind (CFDI tipo E) ----

ALTER TABLE invoice ADD COLUMN return_id uuid;
ALTER TABLE invoice ADD COLUMN related_invoice_id uuid;

ALTER TABLE invoice DROP CONSTRAINT invoice_kind_check;
ALTER TABLE invoice ADD CONSTRAINT invoice_kind_check
    CHECK (kind IN ('INDIVIDUAL', 'GLOBAL', 'CREDIT_NOTE'));

ALTER TABLE invoice ADD CONSTRAINT fk_invoice_return
    FOREIGN KEY (return_id, tenant_id) REFERENCES sale_return (id, tenant_id);
ALTER TABLE invoice ADD CONSTRAINT fk_invoice_related
    FOREIGN KEY (related_invoice_id, tenant_id) REFERENCES invoice (id, tenant_id);

-- The ONE-active-invoice-per-sale guard re-scopes to INGRESO documents: a
-- credit note (return_id set, sale_id NULL) must never collide with the
-- sale's own FA, and N partial returns keep N separate NCs.
DROP INDEX uq_invoice_sale_active;
CREATE UNIQUE INDEX uq_invoice_sale_active ON invoice (tenant_id, sale_id)
    WHERE sale_id IS NOT NULL AND return_id IS NULL
      AND status IN ('PENDING', 'STAMPED');

-- ONE ACTIVE credit note per return. Cancelling the NC (SAT motive 01/02/03)
-- frees the return for a corrected re-issuance — same rule as the FA.
CREATE UNIQUE INDEX uq_invoice_return_active ON invoice (tenant_id, return_id)
    WHERE return_id IS NOT NULL AND status IN ('PENDING', 'STAMPED');

CREATE INDEX idx_invoice_tenant_return ON invoice (tenant_id, return_id);

-- ---- RLS (same contract as V7/V10-V15) ----
ALTER TABLE return_folio      ENABLE ROW LEVEL SECURITY;
ALTER TABLE sale_return       ENABLE ROW LEVEL SECURITY;
ALTER TABLE sale_return_line  ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON return_folio
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON sale_return
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON sale_return_line
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- ---- Privilege sweep ----
GRANT SELECT, INSERT, UPDATE, DELETE
    ON return_folio, sale_return, sale_return_line
    TO posplatform_app;
