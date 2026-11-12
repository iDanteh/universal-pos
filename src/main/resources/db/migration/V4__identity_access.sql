-- ============================================================
-- POS PLATFORM - V4 identity & access
--
-- permission       : PLATFORM catalog (seeded manifest, no runtime seeds in code)
-- role             : role definition scoped to one organization
-- role_permission  : role ↔ permission grants
-- membership       : the tenancy grant (organization, user) - unique pair
-- membership_role  : join with SAME-TENANT integrity enforced by composite FKs
-- ============================================================

CREATE TABLE permission (
    id          uuid PRIMARY KEY,
    code        varchar(80) NOT NULL UNIQUE,
    name        varchar(120) NOT NULL,
    description varchar(255),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

INSERT INTO permission (id, code, name, description) VALUES
    (gen_random_uuid(), 'org.view',            'Ver organización',              NULL),
    (gen_random_uuid(), 'org.manage',          'Administrar organización',      NULL),
    (gen_random_uuid(), 'org.settings.manage', 'Configurar la organización',    NULL),
    (gen_random_uuid(), 'users.manage',        'Administrar usuarios',          NULL),
    (gen_random_uuid(), 'roles.manage',        'Administrar roles y permisos',  NULL),
    (gen_random_uuid(), 'subscription.view',   'Ver suscripción',               NULL),
    (gen_random_uuid(), 'subscription.manage', 'Administrar suscripción',       NULL),
    (gen_random_uuid(), 'branch.view',         'Ver sucursales',                NULL),
    (gen_random_uuid(), 'branch.manage',       'Administrar sucursales',        NULL),
    (gen_random_uuid(), 'catalog.read',        'Consultar catálogo',            NULL),
    (gen_random_uuid(), 'catalog.write',       'Administrar catálogo',          NULL),
    (gen_random_uuid(), 'inventory.read',      'Consultar inventario',          NULL),
    (gen_random_uuid(), 'inventory.write',     'Administrar inventario',        NULL),
    (gen_random_uuid(), 'sales.read',          'Consultar ventas',              NULL),
    (gen_random_uuid(), 'sales.execute',       'Ejecutar ventas',               NULL),
    (gen_random_uuid(), 'fiscal.read',         'Consultar facturación',         NULL);

CREATE TABLE role (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization (id),
    tenant_id       uuid NOT NULL,
    name            varchar(80) NOT NULL,
    description     varchar(255),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (tenant_id = organization_id),
    UNIQUE (organization_id, name)
);

CREATE UNIQUE INDEX uq_role_id_tenant ON role (id, tenant_id);
CREATE INDEX idx_role_tenant ON role (tenant_id);

CREATE TABLE role_permission (
    role_id       uuid NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    permission_id uuid NOT NULL REFERENCES permission (id),
    PRIMARY KEY (role_id, permission_id)
);

CREATE INDEX idx_role_permission_permission ON role_permission (permission_id);

CREATE TABLE membership (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization (id),
    tenant_id       uuid NOT NULL,
    user_id         uuid NOT NULL REFERENCES app_user (id),
    status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (tenant_id = organization_id),
    UNIQUE (organization_id, user_id)
);

CREATE UNIQUE INDEX uq_membership_id_tenant ON membership (id, tenant_id);
CREATE INDEX idx_membership_tenant ON membership (tenant_id);
CREATE INDEX idx_membership_user ON membership (user_id);

CREATE TABLE membership_role (
    id            uuid PRIMARY KEY,
    membership_id uuid NOT NULL REFERENCES membership (id) ON DELETE CASCADE,
    role_id       uuid NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    tenant_id     uuid NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (membership_id, tenant_id) REFERENCES membership (id, tenant_id),
    FOREIGN KEY (role_id, tenant_id)       REFERENCES role (id, tenant_id),
    UNIQUE (membership_id, role_id)
);

CREATE INDEX idx_membership_role_tenant ON membership_role (tenant_id);
