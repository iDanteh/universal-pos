-- ============================================================
-- POS PLATFORM - V17 users, roles & permissions (fase 10 · ADR-020)
--
-- membership  : gains the invitation lifecycle. Status PENDING already
--               existed (V4); an invited member is PENDING until the token
--               is consumed (POST /auth/invitations/accept), which activates
--               the grant. Never deleted (ADR-011), revocation stays.
--                 invitation_token_hash : SHA-256 of "<tenantId>:<secret>" —
--               the token is the credential; the tenant id rides in clear
--               ONLY so the accept endpoint (which has NO session and NO
--               tenant) can open the right RLS scope to find the row.
--                 invitation_expires_at : 7 days from issuance.
-- role        : gains is_system. Provisioning-created roles (OWNER) are
--               immutable for the roles API. Pre-v0.10 tenants only ever had
--               provisioning roles, so V17 marks ALL existing roles system
--               and grants them the 2 new permission codes — an existing
--               tenant cannot lose access after the migration.
-- permission  : 2 new codes (same "migrations carry the manifest" rule as
--               V4/V15): fiscal.write (issue/cancel CFDI + credit notes) and
--               cash.operate (open/count/close the drawer). Catalog = 18.
--               RULE: any FUTURE permission code must be granted to existing
--               is_system roles in the same migration.
--
-- No new tables: RLS stays at 26 tables with policies (V7..V16).
-- ============================================================

-- ---- membership gains the invitation lifecycle ----
ALTER TABLE membership ADD COLUMN invitation_token_hash   varchar(64);
ALTER TABLE membership ADD COLUMN invitation_expires_at   timestamptz;
ALTER TABLE membership ADD COLUMN invitation_accepted_at  timestamptz;
ALTER TABLE membership ADD COLUMN invited_by              uuid;

CREATE UNIQUE INDEX uq_membership_invitation_token
    ON membership (invitation_token_hash)
    WHERE invitation_token_hash IS NOT NULL;
CREATE INDEX idx_membership_invited_by ON membership (tenant_id, invited_by);

-- ---- role gains the system flag ----
ALTER TABLE role ADD COLUMN is_system boolean NOT NULL DEFAULT false;

-- ---- permission catalog: 2 new codes + grant to existing system roles ----
INSERT INTO permission (id, code, name, description) VALUES
    (gen_random_uuid(), 'fiscal.write', 'Emitir y cancelar facturación', NULL),
    (gen_random_uuid(), 'cash.operate', 'Operar la caja',                NULL);

-- Pre-v0.10 roles are provisioning roles (no roles API existed): mark them
-- system and grant the new codes so existing owners keep full access.
UPDATE role SET is_system = true;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
JOIN permission p ON p.code IN ('fiscal.write', 'cash.operate')
WHERE r.is_system = true;

-- ---- Privilege sweep ----
-- No new tables/column grants needed: V7 already granted SELECT, INSERT,
-- UPDATE, DELETE ON ALL TABLES to posplatform_app (table-level grants cover
-- the new columns), and ALTER DEFAULT PRIVILEGES (V1) covers the indexes.
