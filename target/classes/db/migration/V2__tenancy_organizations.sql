-- ============================================================
-- POS PLATFORM - V2 tenancy organizations
--
-- app_user     : PLATFORM-scoped global identity (no RLS; a person exists once)
-- organization : the tenant root (RLS compares its own id to the tenant setting)
-- legal_entity : razón social (tenant-scoped; tenant_id = organization_id)
-- branch       : sucursal (via legal entity; composite FK prevents cross-tenant refs)
-- ============================================================

CREATE TABLE app_user (
    id            uuid PRIMARY KEY,
    email         varchar(320) NOT NULL UNIQUE,
    display_name  varchar(120) NOT NULL,
    password_hash varchar(255),
    status        text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'BLOCKED')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_app_user_status ON app_user (status);

CREATE TABLE organization (
    id         uuid PRIMARY KEY,
    name       varchar(120) NOT NULL,
    status     text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE legal_entity (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization (id),
    tenant_id       uuid NOT NULL,
    legal_name      varchar(180) NOT NULL,
    trade_name      varchar(180),
    tax_id          varchar(32),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (tenant_id = organization_id)
);

CREATE UNIQUE INDEX uq_legal_entity_id_tenant ON legal_entity (id, tenant_id);
CREATE INDEX idx_legal_entity_tenant ON legal_entity (tenant_id);
CREATE INDEX idx_legal_entity_org ON legal_entity (organization_id);

CREATE TABLE branch (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL,
    legal_entity_id uuid NOT NULL,
    name            varchar(120) NOT NULL,
    city            varchar(120),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (legal_entity_id, tenant_id) REFERENCES legal_entity (id, tenant_id)
);

CREATE INDEX idx_branch_tenant ON branch (tenant_id);
