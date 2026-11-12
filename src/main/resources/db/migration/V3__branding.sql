-- ============================================================
-- POS PLATFORM - V3 branding (White Label, ADR-007)
--
-- brand              : PLATFORM-scoped commercial identity
-- reseller           : PLATFORM-scoped distributor
-- organization_brand : TENANT-scoped relation org ↔ brand
-- ============================================================

CREATE TABLE brand (
    id         uuid PRIMARY KEY,
    name       varchar(120) NOT NULL UNIQUE,
    config     jsonb,
    status     text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE reseller (
    id         uuid PRIMARY KEY,
    name       varchar(120) NOT NULL UNIQUE,
    brand_id   uuid REFERENCES brand (id),
    status     text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE organization_brand (
    id                uuid PRIMARY KEY,
    organization_id   uuid NOT NULL REFERENCES organization (id),
    brand_id          uuid NOT NULL REFERENCES brand (id),
    tenant_id         uuid NOT NULL,
    relationship_type varchar(80),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CHECK (tenant_id = organization_id)
);

CREATE UNIQUE INDEX uq_organization_brand ON organization_brand (organization_id, brand_id);
CREATE INDEX idx_organization_brand_tenant ON organization_brand (tenant_id);
