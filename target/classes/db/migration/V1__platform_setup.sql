-- ============================================================
-- POS PLATFORM - V1 platform setup
--
-- Default privileges: every table/sequence created later by the migrator is
-- automatically usable by the restricted runtime role (posplatform_app),
-- which has NO DDL rights and is NOT the owner of anything (see
-- infrastructure/postgres/init/01-roles.sh and docs/architecture).
-- ============================================================

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO posplatform_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO posplatform_app;
