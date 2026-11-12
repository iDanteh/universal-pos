-- ============================================================
-- POS PLATFORM - V13 fiscal invoicing core (ADR-006 + fiscal.md)
--
-- legal_entity  : gains the EMISOR fiscal face (ADR-006 rule 1): régimen
--                 fiscal SAT + código postal (LugarExpedicion). Optional
--                 columns: an entity without them simply cannot issue yet.
-- sat_catalog   : GLOBAL (not tenant-scoped, no RLS) versioned SAT codes
--                 (ADR-006 rule 4: catalogs live in MIGRATIONS, never
--                 hardcoded in logic). Seed keeps the sandbox set minimal;
--                 new entries arrive as new migrations, never UPDATE games.
-- invoice_folio : per-tenant + serie folio counter (same atomic pattern as
--                 V11 sale_folio: ON CONFLICT ... RETURNING in-transaction).
-- invoice       : the CFDI 4.0 document root. The lifecycle machine lives
--                 HERE, not in sales (ADR-006 rule 3): PENDING -> STAMPED
--                 -> CANCELED (+ERROR). Receptor data is a SNAPSHOT too,
--                 and the folio fiscal (UUID) comes from the FiscalProvider
--                 port (Mock PAC in this phase).
-- invoice_line  : SNAPSHOT line copied from the sale (or aggregated for the
--                 global invoice) at emission time.
--
-- Integrity: partial UNIQUE indexes keep ONE ACTIVE invoice per sale
-- and one ACTIVE global invoice per (tenant, month) while still allowing a
-- clean re-issue AFTER a cancellation with motive 01/02/03. Composite FKs
-- (id, tenant_id) follow the V10/V11/V12 pattern. RLS: tenant_isolation on
-- invoice_* tables; sat_catalog is public reference data (SELECT-only).
-- ============================================================

-- ---- Emisor fiscal face (ADR-006 rule 1: fiscalidad by razón social) ----
ALTER TABLE legal_entity ADD COLUMN fiscal_regime text;
ALTER TABLE legal_entity ADD COLUMN zip_code varchar(5);

-- ---- SAT catalogs (global reference data) ----
CREATE TABLE sat_catalog (
    id          uuid PRIMARY KEY,
    catalog     text NOT NULL CHECK (catalog IN
                ('REGIMEN', 'USO_CFDI', 'FORMA_PAGO', 'METODO_PAGO',
                 'MOTIVO_CANCELACION', 'UNIDAD', 'PRODUCTO_SERVICIO')),
    code        varchar(16) NOT NULL,
    description text NOT NULL,
    active      boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (catalog, code)
);

CREATE INDEX idx_sat_catalog_lookup ON sat_catalog (catalog) WHERE active;

INSERT INTO sat_catalog (id, catalog, code, description) VALUES
    -- Régimen fiscal del emisor (Anexo 20)
    (gen_random_uuid(), 'REGIMEN', '601', 'General de Ley Personas Morales'),
    (gen_random_uuid(), 'REGIMEN', '603', 'Personas Morales con Fines no Lucrativos'),
    (gen_random_uuid(), 'REGIMEN', '605', 'Sueldos y Salarios y Asimilados a Salarios'),
    (gen_random_uuid(), 'REGIMEN', '606', 'Arrendamiento'),
    (gen_random_uuid(), 'REGIMEN', '607', 'Régimen de Enajenación o Adquisición de Bienes'),
    (gen_random_uuid(), 'REGIMEN', '608', 'Demás ingresos'),
    (gen_random_uuid(), 'REGIMEN', '609', 'Consolidación'),
    (gen_random_uuid(), 'REGIMEN', '610', 'Dividendos y Beneficios Fiscales'),
    (gen_random_uuid(), 'REGIMEN', '611', 'Personas Físicas con Actividad Empresarial y Profesionales'),
    (gen_random_uuid(), 'REGIMEN', '612', 'Personas Físicas con Actividad Empresarial y Profesionales (transitorio 2014)'),
    (gen_random_uuid(), 'REGIMEN', '616', 'Sin Obligaciones Fiscales'),
    (gen_random_uuid(), 'REGIMEN', '620', 'Sociedades Cooperativas de Producción que optan por diferir sus ingresos'),
    (gen_random_uuid(), 'REGIMEN', '623', 'Opciones Listadas en el Mercado de Valores'),
    (gen_random_uuid(), 'REGIMEN', '626', 'Régimen Simplificado de Confianza'),
    -- Uso del CFDI (Anexo 20)
    (gen_random_uuid(), 'USO_CFDI', 'G01', 'Adquisición de mercancías'),
    (gen_random_uuid(), 'USO_CFDI', 'G02', 'Devoluciones, descuentos o bonificaciones'),
    (gen_random_uuid(), 'USO_CFDI', 'G03', 'Gastos en general'),
    (gen_random_uuid(), 'USO_CFDI', 'I01', 'Construcciones'),
    (gen_random_uuid(), 'USO_CFDI', 'I02', 'Mobiliario y equipo de oficina por inversiones'),
    (gen_random_uuid(), 'USO_CFDI', 'I03', 'Equipo de transporte'),
    (gen_random_uuid(), 'USO_CFDI', 'P01', 'Por definir'),
    -- Forma de pago (catálogo Anexo 20)
    (gen_random_uuid(), 'FORMA_PAGO', '01', 'Efectivo'),
    (gen_random_uuid(), 'FORMA_PAGO', '03', 'Transferencia electrónica de fondos'),
    (gen_random_uuid(), 'FORMA_PAGO', '04', 'Tarjeta de crédito, débito o de servicios'),
    (gen_random_uuid(), 'FORMA_PAGO', '28', 'Tarjeta de regalo electrónica'),
    (gen_random_uuid(), 'FORMA_PAGO', '99', 'Por definir'),
    -- Método de pago
    (gen_random_uuid(), 'METODO_PAGO', 'PUE', 'Pago en una sola exhibición'),
    (gen_random_uuid(), 'METODO_PAGO', 'PPD', 'Pago en parcialidades o diferido'),
    -- Motivos de cancelación (Anexo 20)
    (gen_random_uuid(), 'MOTIVO_CANCELACION', '01', 'Comprobante emitido con errores con relación'),
    (gen_random_uuid(), 'MOTIVO_CANCELACION', '02', 'Comprobante emitido con errores sin relación'),
    (gen_random_uuid(), 'MOTIVO_CANCELACION', '03', 'No se llevó a cabo la operación'),
    (gen_random_uuid(), 'MOTIVO_CANCELACION', '04', 'Operación nominaria relacionada en el comprobante global'),
    -- Unidades (Anexo 20, subset sandbox)
    (gen_random_uuid(), 'UNIDAD', 'H87', 'Pieza'),
    (gen_random_uuid(), 'UNIDAD', 'KGM', 'Kilogramo'),
    (gen_random_uuid(), 'UNIDAD', 'LTR', 'Litro'),
    (gen_random_uuid(), 'UNIDAD', 'MTK', 'Metro cuadrado'),
    (gen_random_uuid(), 'UNIDAD', 'E48', 'Unidad de servicio'),
    -- Productos y servicios (Anexo 20, subset sandbox)
    (gen_random_uuid(), 'PRODUCTO_SERVICIO', '01010101', 'Enajenación de bienes por contrato de mutuo mercantil'),
    (gen_random_uuid(), 'PRODUCTO_SERVICIO', '50211500', 'Ropa de trabajo'),
    (gen_random_uuid(), 'PRODUCTO_SERVICIO', '50211600', 'Otros accesorios de uso en la empresa'),
    (gen_random_uuid(), 'PRODUCTO_SERVICIO', '51112000', 'Embalajes, etiquetas y envolturas'),
    (gen_random_uuid(), 'PRODUCTO_SERVICIO', '81111502', 'Servicios de venta de alimentos preparados para consumo inmediato');

-- ---- Folio counter per tenant + serie ----
CREATE TABLE invoice_folio (
    tenant_id uuid NOT NULL REFERENCES organization (id),
    serie     text NOT NULL,
    last      bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, serie)
);

-- ---- CFDI 4.0 document root ----
CREATE TABLE invoice (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES organization (id),
    legal_entity_id uuid NOT NULL,
    serie           text NOT NULL DEFAULT 'FA',
    folio           bigint NOT NULL,
    kind            text NOT NULL CHECK (kind IN ('INDIVIDUAL', 'GLOBAL')),
    -- the issued operation; NULL for the global invoice (N operations inside)
    sale_id         uuid,
    -- receptor SNAPSHOT (v0.6 copies what was captured at emission)
    receptor_rfc    varchar(13),
    receptor_name   varchar(180),
    receptor_regime text,
    receptor_use    text,
    receptor_zip    varchar(5),
    -- GLOBAL period
    period_year     int CHECK (period_year BETWEEN 2020 AND 2100),
    period_month    int CHECK (period_month BETWEEN 1 AND 12),
    currency        text NOT NULL DEFAULT 'MXN',
    -- totals are the SALE snapshot (tax domain ADR-006 arrives later);
    -- the CFDI NEVER rebills an amount different from what was paid.
    subtotal        numeric(14, 2) NOT NULL,
    discount_total  numeric(14, 2) NOT NULL DEFAULT 0,
    tax_total       numeric(14, 2) NOT NULL DEFAULT 0,
    total           numeric(14, 2) NOT NULL,
    status          text NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'STAMPED', 'CANCELED', 'ERROR')),
    status_reason   text,
    -- stamp (FiscalProvider port; MOCK in this phase)
    fiscal_id       uuid,
    provider        text NOT NULL DEFAULT 'MOCK',
    stamped_at      timestamptz,
    -- cancellation
    cancel_motive   varchar(2),
    cancel_note     varchar(280),
    -- generated document preview (JSON: provider info + XML preview)
    sat_document    jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, serie, folio),
    FOREIGN KEY (legal_entity_id, tenant_id) REFERENCES legal_entity (id, tenant_id),
    FOREIGN KEY (sale_id, tenant_id) REFERENCES sale (id, tenant_id)
);

-- ONE ACTIVE invoice per sale (PENDING/STAMPED). After cancelling with
-- motive 01/02/03 the sale becomes issueable again (a NEW invoice row).
CREATE UNIQUE INDEX uq_invoice_sale_active ON invoice (tenant_id, sale_id)
    WHERE sale_id IS NOT NULL AND status IN ('PENDING', 'STAMPED');

-- ONE ACTIVE GLOBAL invoice per (tenant, year, month).
CREATE UNIQUE INDEX uq_invoice_global_period ON invoice (tenant_id, period_year, period_month)
    WHERE kind = 'GLOBAL' AND status IN ('PENDING', 'STAMPED');

CREATE INDEX idx_invoice_tenant_created ON invoice (tenant_id, created_at DESC);
CREATE INDEX idx_invoice_tenant_status  ON invoice (tenant_id, status);
CREATE INDEX idx_invoice_tenant_sale    ON invoice (tenant_id, sale_id);
CREATE INDEX idx_invoice_tenant_legal   ON invoice (tenant_id, legal_entity_id);

CREATE TABLE invoice_line (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES organization (id),
    invoice_id   uuid NOT NULL,
    sale_line_id uuid,                       -- provenance (NULL in global aggregation)
    description  varchar(280) NOT NULL,
    sku          varchar(64),
    unit_sat     text NOT NULL,
    quantity     numeric(14, 4) NOT NULL CHECK (quantity > 0),
    unit_price   numeric(14, 4) NOT NULL CHECK (unit_price >= 0),
    discount     numeric(14, 4) NOT NULL DEFAULT 0 CHECK (discount >= 0),
    line_total   numeric(14, 2) NOT NULL CHECK (line_total >= 0),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoice (id, tenant_id)
);

CREATE INDEX idx_invoice_line_tenant  ON invoice_line (tenant_id);
CREATE INDEX idx_invoice_line_invoice ON invoice_line (invoice_id);

-- ---- RLS (same contract as V7/V10/V11/V12) ----
ALTER TABLE invoice_folio ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice       ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice_line  ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON invoice_folio
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON invoice
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON invoice_line
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- ---- Privilege sweep (sat_catalog is global reference data: SELECT only) ----
GRANT SELECT, INSERT, UPDATE, DELETE
    ON invoice_folio, invoice, invoice_line
    TO posplatform_app;
GRANT SELECT ON sat_catalog TO posplatform_app;
