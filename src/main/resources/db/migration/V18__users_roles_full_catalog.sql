-- ============================================================
-- POS PLATFORM - V18 users roles full-catalog repair (fase 10 · ADR-020)
--
-- Discovered by the v0.10 smoke on the pre-v0.10 demo tenant: V17 granted
-- existing is_system roles ONLY the 2 codes it seeded, but a pre-enforcement
-- OWNER could touch EVERYTHING (no guard existed), and the pre-v0.10
-- provisioning manifest carried just 8 of the then-16 codes. With
-- PermissionGuard live, such an owner would have been locked out of
-- catalog/sales/inventory/fiscal the moment v0.10 deployed.
--
-- RULE (migrations.md #7): a permission code that migrates in migrates in
-- GRANTED to the system roles — the "restore full powers" principle. This
-- repair grants the COMPLETE catalog to every is_system role (idempotent,
-- PK (role_id, permission_id) ON CONFLICT).
-- ============================================================

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
JOIN permission p ON true
WHERE r.is_system = true
ON CONFLICT (role_id, permission_id) DO NOTHING;
