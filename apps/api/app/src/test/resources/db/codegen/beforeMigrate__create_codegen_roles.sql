-- Generation-only role bootstrap (design.md decision 5). The jOOQ code-generation container
-- (task 1.7) runs the real migrations against a throwaway PostgreSQL instance so that a GRANT
-- statement in a migration has a role to name — but that container never needs anyone to actually
-- log in, so every role here is NOLOGIN and carries no password at all. This is a Flyway SQL
-- callback (docs/06-estrategia-de-testing.md; naming convention beforeMigrate__<description>.sql),
-- not a versioned migration: it lives in src/test/resources, never under classpath:db/migration,
-- and never ships in the deployable artifact.
--
-- docs/06-estrategia-de-testing.md section 14.2 (lines 648-649): a migration must never carry a
-- password. This file is not a migration and carries none anyway (NOLOGIN roles cannot
-- authenticate, so there is nothing to protect).
CREATE ROLE confia_owner       NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE ROLE confia_admin_app   NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE ROLE confia_portal_app  NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE ROLE confia_readonly    NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE ROLE confia_backup      NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
