-- ============================================================
-- POS PLATFORM - V7 Row Level Security (defense in depth)
--
-- Policies use the transaction-scoped GUC 'app.tenant_id' set by the
-- application with set_config(..., true) (SET LOCAL semantics) so pooled
-- connections (HikariCP) can never leak tenant context across transactions.
--
-- The runtime role (posplatform_app) is NOT the table owner and has NO
-- BYPASSRLS: it cannot escape these policies. The migrator role owns the
-- tables and may bypass RLS by design (DDL/migrations only).
-- ============================================================

ALTER TABLE organization       ENABLE ROW LEVEL SECURITY;
ALTER TABLE legal_entity       ENABLE ROW LEVEL SECURITY;
ALTER TABLE branch             ENABLE ROW LEVEL SECURITY;
ALTER TABLE role               ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership         ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership_role    ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscription       ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_brand ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_key    ENABLE ROW LEVEL SECURITY;

-- The tenant root: policy compares the row id with the transaction tenant.
CREATE POLICY tenant_isolation ON organization
    FOR ALL
    USING (id = current_setting('app.tenant_id', true)::uuid);

-- All tenant-scoped tables: policy compares tenant_id.
CREATE POLICY tenant_isolation ON legal_entity
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON branch
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON role
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON membership
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON membership_role
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON subscription
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON organization_brand
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON idempotency_key
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Privilege sweep (belt & suspenders on top of default privileges in V1).
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO posplatform_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO posplatform_app;
