-- V10: the technical table of the db-scheduler library (background-jobs-with-db-scheduler design.md
-- decision 15; ADR-0016 rules 1 to 3; ADR-0017 rule 6).
--
-- scheduled_tasks belongs to the closed catalogue of four technical tables of ADR-0017: it has no
-- institution_id, no row-level security and no module prefix, because the library owns its name and
-- its columns and a task row is not business data. It carries only the task name, an opaque instance
-- identifier and a serialized envelope with identifiers; the strict serializer and the allow-list of
-- fields that guarantee that arrive with the scheduling port (ADR-0016 compliance rule 6).
--
-- The DDL is the PostgreSQL schema of db-scheduler 16.12.0, copied from the library
-- (db-scheduler/src/test/resources/postgresql_tables.sql at tag v16.12.0). ScheduledTasksSchemaIT
-- applies that file to a scratch schema and compares its catalogue with this table, so a library
-- update that changes the schema breaks the build. Two things differ from the library file, on
-- purpose and without effect on the library: the indexes carry the table name as a prefix (the
-- library never refers to an index by name), and the table has a comment that cites its ADRs.
--
-- The library never creates this table (probe P-9; ScheduledTasksNotCreatedAtRuntimeIT), and no
-- application role has CREATE on the schema: this migration, run by confia_owner, is the only way
-- the table comes to exist. jOOQ does not generate code for it (ADR-0017 rule 6; the exclusion is in
-- app/pom.xml); the library reaches it through JDBC alone.
CREATE TABLE scheduled_tasks (
    task_name            TEXT                     NOT NULL,
    task_instance        TEXT                     NOT NULL,
    task_data            BYTEA,
    execution_time       TIMESTAMP WITH TIME ZONE NOT NULL,
    picked               BOOLEAN                  NOT NULL,
    picked_by            TEXT,
    last_success         TIMESTAMP WITH TIME ZONE,
    last_failure         TIMESTAMP WITH TIME ZONE,
    consecutive_failures INT,
    last_heartbeat       TIMESTAMP WITH TIME ZONE,
    version              BIGINT                   NOT NULL,
    priority             SMALLINT,
    PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX scheduled_tasks_execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX scheduled_tasks_last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX scheduled_tasks_priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time ASC);

COMMENT ON TABLE scheduled_tasks IS
    'Technical table of db-scheduler (ADR-0016), in the closed catalogue of ADR-0017: no institution_id and no row-level security. Written by the administrative and portal processes inside the business transaction, executed only by the worker. Never edited by hand except to reschedule a parked execution (docs/07 section 11.1).';

-- Privileges (design.md decision 15; docs/03-seguridad.md section 6.1). REVOKE always precedes any
-- GRANT. The matrix is exact and RolePrivilegeMatrixIT asserts it for the five roles.
REVOKE ALL ON scheduled_tasks FROM PUBLIC;

-- The worker connects as confia_admin_app: it polls (SELECT), picks and completes executions
-- (UPDATE), schedules the next occurrence of a recurring task (INSERT) and removes a finished
-- execution (DELETE). This is the only DELETE granted on a table to an application role, and it is
-- on a technical table that holds no business data; the purge of business tables never uses DELETE
-- (ADR-0025, proposed).
GRANT SELECT, INSERT, UPDATE, DELETE ON scheduled_tasks TO confia_admin_app;

-- The portal only schedules: INSERT and SELECT, and never UPDATE or DELETE, so a compromised portal
-- cannot alter or erase a task. SELECT is here because probe P-6 (PortalSchedulingPrivilegeIT) proved
-- that scheduleIfNotExists reads the row before it inserts and is refused with INSERT alone (and the
-- plain schedule call is built on it); the scheduling port uses it anyway, because a duplicate
-- instance would otherwise abort the whole business transaction (design.md decision 6).
-- Consequence: the portal can read the queue table, whose envelope carries identifiers and no
-- personal data by construction (design.md decision 8). The grant is justified by the scheduling
-- client, not by health: whether the portal readiness group reports the queue stays a decision of
-- the observability changes (docs/07 section 9).
GRANT SELECT, INSERT ON scheduled_tasks TO confia_portal_app;

-- confia_readonly: no GRANT at all (docs/03 section 6.1: a task row can carry business identifiers).
-- confia_owner: schema owner, not subject to GRANT/REVOKE, retains every privilege by definition.
-- confia_backup: reads through pg_read_all_data, already granted on the whole schema, which never
-- bypasses row-level security (and this table has none).
