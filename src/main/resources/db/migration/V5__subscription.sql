-- ============================================================
-- POS PLATFORM - V5 subscriptions
--
-- plan : PLATFORM catalog of commercial plans (seeded with 'trial')
-- subscription : TENANT-scoped; single ACTIVE/TRIAL per organization
-- ============================================================

CREATE TABLE plan (
    id            uuid PRIMARY KEY,
    code          varchar(40) NOT NULL UNIQUE,
    name          varchar(120) NOT NULL,
    description   varchar(255),
    monthly_price numeric(12, 2),
    currency      char(3) NOT NULL DEFAULT 'MXN',
    features      jsonb,
    active        boolean NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

INSERT INTO plan (id, code, name, description, monthly_price, currency, features, active)
VALUES (gen_random_uuid(), 'trial', 'Prueba gratuita', 'Plan de prueba de 14 días',
        NULL, 'MXN', '["pos", "inventory"]'::jsonb, true);

CREATE TABLE subscription (
    id                   uuid PRIMARY KEY,
    organization_id      uuid NOT NULL REFERENCES organization (id),
    plan_id              uuid NOT NULL REFERENCES plan (id),
    tenant_id            uuid NOT NULL,
    status               text NOT NULL DEFAULT 'TRIAL'
                         CHECK (status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'CANCELLED', 'EXPIRED')),
    started_at           timestamptz NOT NULL DEFAULT now(),
    current_period_start timestamptz,
    current_period_end   timestamptz,
    cancelled_at         timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CHECK (tenant_id = organization_id)
);

CREATE INDEX idx_subscription_tenant ON subscription (tenant_id);

CREATE UNIQUE INDEX uq_subscription_active_org
    ON subscription (organization_id) WHERE status IN ('ACTIVE', 'TRIAL');
