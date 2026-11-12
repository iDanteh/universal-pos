-- Integration-test database initialization: creates the same least-privilege
-- role model used in compose deployment (01-roles.sh) with throwaway passwords.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'posplatform_migrator') THEN
        CREATE ROLE posplatform_migrator LOGIN PASSWORD 'it-migrator' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'posplatform_app') THEN
        CREATE ROLE posplatform_app LOGIN PASSWORD 'it-app' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END
$$;

GRANT CONNECT ON DATABASE test TO posplatform_app, posplatform_migrator;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO posplatform_migrator;
GRANT USAGE ON SCHEMA public TO posplatform_app;
