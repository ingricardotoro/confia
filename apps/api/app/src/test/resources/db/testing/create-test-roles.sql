-- Test-only role bootstrap (design.md decision 4; proposal.md scope point 2; docs/03-seguridad.md
-- section 6.1). Mounted at /docker-entrypoint-initdb.d/01-create-test-roles.sql by
-- PostgresIntegrationTest, never via Testcontainers' withInitScript(...): that mechanism runs the
-- file through Testcontainers' own statement splitter, which is sensitive to DO $$ ... $$ blocks
-- that a future migration or callback in this repository may need.
--
-- Runs as the container's own bootstrap superuser ("postgres", never confia_owner: decision 4).
-- Every role below is a literal test credential (docs/06-estrategia-de-testing.md section 14.2),
-- never a real secret; CLAUDE.md rule 13 does not apply to a hardcoded, publicly-known test-only
-- password used only inside an ephemeral Testcontainers instance.
CREATE ROLE confia_owner       LOGIN PASSWORD 'test-only-not-a-secret' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE ROLE confia_admin_app   LOGIN PASSWORD 'test-only-not-a-secret' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE ROLE confia_portal_app  LOGIN PASSWORD 'test-only-not-a-secret' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE ROLE confia_readonly    LOGIN PASSWORD 'test-only-not-a-secret' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE ROLE confia_backup      LOGIN PASSWORD 'test-only-not-a-secret' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

-- confia_backup reads everything for the backup job (docs/03 section 6.1, "pg_read_all_data").
GRANT pg_read_all_data TO confia_backup;

-- Migrations run as confia_owner (design.md decision 1: GRANTs travel in the migration, CREATE
-- ROLE never does), so confia_owner must own the database and the public schema, never the
-- container's bootstrap superuser.
ALTER DATABASE confia_test OWNER TO confia_owner;
ALTER SCHEMA public OWNER TO confia_owner;

-- flyway_schema_history does not exist yet when this script runs (it is Flyway's own bookkeeping
-- table, created the first time confia_owner migrates): grant SELECT on it here, in a DO block
-- executed after Flyway's first run would be circular, so instead confia_admin_app and
-- confia_portal_app receive this same GRANT again from the real V1 migration in PR A2
-- (docs/03 section 6.1, "para la comprobación de salud de migraciones aplicadas"; ADR-0017). This
-- test-only script cannot grant privileges on a table that is not created yet, so
-- DatabasePipelineIT (task 1.4/1.5) only checks that the table exists; it does not need to read
-- it as confia_admin_app before the real migration exists in A2.
