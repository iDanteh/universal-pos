-- ============================================================
-- POS PLATFORM - V9 identity sessions (Fase 2, autenticación real)
--
-- PLATFORM-scoped tables (NO RLS on purpose): a session belongs to
-- A PERSON, not to a tenant. The tenant flow stays server-side:
-- the browser only holds an opaque HttpOnly cookie, every tenant
-- claim is verified against real membership inside the RLS scope.
--
-- app_session           : DB-backed sessions. We persist the SHA-256
--                         of the raw token; the raw token lives ONLY in
--                         the browser cookie (AES-free, revocable).
-- app_user_organization : identity-resolution mirror written ONLY by
--                         trusted onboarding code right after a verified
--                         Membership insert. Source of truth remains
--                         membership; this mirror exists because RLS
--                         (by design) cannot do cross-tenant reads.
-- ============================================================

CREATE TABLE app_session (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES app_user (id),
    token_hash varchar(64) NOT NULL UNIQUE,
    status     text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_app_session_user_status ON app_session (user_id, status);
CREATE INDEX idx_app_session_expires ON app_session (expires_at);

CREATE TABLE app_user_organization (
    id                uuid PRIMARY KEY,
    user_id           uuid NOT NULL REFERENCES app_user (id),
    organization_id   uuid NOT NULL,
    organization_name varchar(120) NOT NULL,
    is_primary        boolean NOT NULL DEFAULT true,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_organization UNIQUE (user_id, organization_id)
);
