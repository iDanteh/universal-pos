-- ============================================================
-- POS PLATFORM - V8 vertical profiles + branding depth
--   1) organization.vertical_code  : giro del negocio (ADR-010)
--     declarative; the vertical profile is CODE + migration, not
--     per-tenant custom logic.
--   2) legal_entity: taxpayer id (RFC) unique per tenant — a
--     fiscal identity may only emit documents for ONE reason inside
--     the org (ADR-009).
--   3) organization_brand.overrides: white-label overrides jsonb
--     (ADR-007 declares this table column; materialized here).
-- ============================================================

ALTER TABLE organization
    ADD COLUMN vertical_code varchar(32) NOT NULL DEFAULT 'CUSTOM';

ALTER TABLE organization
    ADD CONSTRAINT ck_organization_vertical CHECK (vertical_code IN
        ('RETAIL', 'RESTAURANT', 'PHARMACY', 'HARDWARE', 'DISTRIBUTION', 'CUSTOM'));

-- Same RFC must not appear twice inside one tenant. Case-insensitive.
CREATE UNIQUE INDEX uq_legal_entity_taxid_per_tenant
    ON legal_entity (tenant_id, lower(tax_id))
    WHERE tax_id IS NOT NULL;

ALTER TABLE organization_brand
    ADD COLUMN overrides jsonb;
