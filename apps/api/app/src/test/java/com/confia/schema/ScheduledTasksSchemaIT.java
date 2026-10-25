package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.support.PostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

/**
 * Scenarios BI62 and BI64 (background-jobs-with-db-scheduler specs/build-integrity/spec.md; design.md
 * decision 15 and probe P-8): {@code V10} creates {@code scheduled_tasks} with the schema db-scheduler
 * 16.12.0 expects, and nothing else. The reference is the library's own PostgreSQL DDL
 * ({@code db-scheduler/src/test/resources/postgresql_tables.sql} at tag {@code v16.12.0}, git blob
 * {@code 6ff4d56f60808570f45dc78f1a94b18c35b6b2c7}), copied as the test resource {@code
 * db/scheduler/postgresql_tables.sql} and pinned by its SHA-256: it is applied to a scratch schema
 * and its catalogue is compared with the real table, so a library update that changes the schema
 * breaks the build instead of the worker (risk R-12).
 */
class ScheduledTasksSchemaIT extends PostgresIntegrationTest {

    private static final String UPSTREAM_DDL = "db/scheduler/postgresql_tables.sql";

    /** SHA-256 of the upstream file with line endings normalized to LF. */
    private static final String UPSTREAM_SHA256 =
            "250c9ce181b49dd0d0b51652c87473ff0fdc0dc8107614e5d9e00e4ea8bb9c15";

    private static final String PROBE_SCHEMA = "scheduler_ddl_probe";

    private static final Pattern STATEMENT_SPLIT = Pattern.compile(";\\s*(?:\\R|$)");

    @Test
    void theReferenceDdlIsTheOneOfThePinnedLibraryVersion() throws Exception {
        String normalized = upstreamDdl().replace("\r\n", "\n");

        assertThat(sha256(normalized)).as("the copied upstream DDL changed: re-read it for the new "
                + "library version and re-pin it").isEqualTo(UPSTREAM_SHA256);
    }

    @Test
    void theCatalogueOfScheduledTasksIsTheOneOfThePinnedLibraryVersion() throws Exception {
        List<String> expectedColumns;
        List<String> expectedIndexes;
        try (Connection superuser = SharedPostgresContainer.connectionAs("postgres");
                Statement statement = superuser.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + PROBE_SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + PROBE_SCHEMA);
            statement.execute("GRANT USAGE ON SCHEMA " + PROBE_SCHEMA + " TO confia_admin_app");
            try {
                statement.execute("SET search_path TO " + PROBE_SCHEMA);
                statement.execute(upstreamDdl());
                statement.execute("RESET search_path");
                expectedColumns = columnsOf(PROBE_SCHEMA + ".scheduled_tasks");
                expectedIndexes = indexShapesOf(PROBE_SCHEMA + ".scheduled_tasks");
            } finally {
                statement.execute("DROP SCHEMA IF EXISTS " + PROBE_SCHEMA + " CASCADE");
            }
        }

        assertThat(expectedColumns).as("non-vacuous: the library table has its twelve columns")
                .hasSize(12);
        assertThat(columnsOf("public.scheduled_tasks"))
                .as("columns, types, nullability and order of public.scheduled_tasks")
                .containsExactlyElementsOf(expectedColumns);
        assertThat(indexShapesOf("public.scheduled_tasks"))
                .as("primary key and index shapes (names differ on purpose: table prefix)")
                .containsExactlyInAnyOrderElementsOf(expectedIndexes);
    }

    @Test
    void theTwelveColumnsAreTheOnesTheLibraryNames() {
        assertThat(columnsOf("public.scheduled_tasks")).extracting(column -> column.split("\\|")[0])
                .containsExactly("task_name", "task_instance", "task_data", "execution_time",
                        "picked", "picked_by", "last_success", "last_failure",
                        "consecutive_failures", "last_heartbeat", "version", "priority");
    }

    @Test
    void everyIndexNameCarriesTheTableNameAsPrefix() {
        List<Record> indexes = dsl.fetch("""
                select c.relname as name, i.indisprimary as is_primary
                  from pg_index i
                  join pg_class c on c.oid = i.indexrelid
                 where i.indrelid = 'public.scheduled_tasks'::regclass
                """);

        assertThat(indexes).extracting(row -> row.get("name", String.class))
                .containsExactlyInAnyOrder("scheduled_tasks_pkey",
                        "scheduled_tasks_execution_time_idx",
                        "scheduled_tasks_last_heartbeat_idx",
                        "scheduled_tasks_priority_execution_time_idx");
    }

    @Test
    void theTableHasNoInstitutionIdNoRowSecurityAndNoPolicy() {
        assertThat(dsl.fetchOne("""
                select count(*) as c from pg_attribute
                 where attrelid = 'public.scheduled_tasks'::regclass
                   and attname = 'institution_id' and not attisdropped
                """).get("c", Number.class).longValue()).isZero();
        Record flags = dsl.fetchOne("""
                select relrowsecurity, relforcerowsecurity from pg_class
                 where oid = 'public.scheduled_tasks'::regclass
                """);
        assertThat(flags.get("relrowsecurity", Boolean.class)).isFalse();
        assertThat(flags.get("relforcerowsecurity", Boolean.class)).isFalse();
        assertThat(dsl.fetchOne("select count(*) as c from pg_policies "
                + "where tablename = 'scheduled_tasks'").get("c", Number.class).longValue())
                .isZero();
    }

    @Test
    void theTableCommentCitesTheTwoAdrsThatGovernIt() {
        String comment = dsl.fetchOne("select obj_description('public.scheduled_tasks'::regclass, "
                + "'pg_class') as comment").get("comment", String.class);

        assertThat(comment).contains("ADR-0016", "ADR-0017");
    }

    @Test
    void theMigrationContainsOnlyTheTableItsIndexesItsCommentAndItsPrivileges() throws IOException {
        String sql = migrationText("V10__create_scheduled_tasks.sql");
        String withoutComments = sql.replaceAll("(?m)--.*$", "");

        List<String> statements = STATEMENT_SPLIT.splitAsStream(withoutComments.strip())
                .map(String::strip).filter(statement -> !statement.isEmpty()).toList();

        assertThat(statements).as("non-vacuous").hasSizeGreaterThanOrEqualTo(8);
        Pattern allowed = Pattern.compile(
                "(?is)^(CREATE TABLE scheduled_tasks\\b.*"
                        + "|CREATE INDEX scheduled_tasks_\\w+ ON scheduled_tasks\\b.*"
                        + "|COMMENT ON TABLE scheduled_tasks\\b.*"
                        + "|REVOKE ALL ON (TABLE )?scheduled_tasks FROM PUBLIC"
                        + "|GRANT [A-Z, ]+ ON (TABLE )?scheduled_tasks TO confia_(admin|portal)_app)$");
        for (String statement : statements) {
            Matcher matcher = allowed.matcher(statement);
            assertThat(matcher.matches())
                    .as("V10 may only create scheduled_tasks, its indexes, its comment and its "
                            + "privileges, but contains: %s", statement)
                    .isTrue();
        }
    }

    private List<String> columnsOf(String qualifiedTable) {
        return dsl.fetch("""
                select a.attname || '|' || format_type(a.atttypid, a.atttypmod) || '|'
                       || a.attnotnull as shape
                  from pg_attribute a
                 where a.attrelid = ?::regclass and a.attnum > 0 and not a.attisdropped
                 order by a.attnum
                """, qualifiedTable).getValues("shape", String.class);
    }

    /** Index definitions without schema and name: {@code USING btree (...)} plus the primary flag. */
    private List<String> indexShapesOf(String qualifiedTable) {
        return dsl.fetch("""
                select i.indisprimary || '|' || substring(pg_get_indexdef(i.indexrelid) from 'USING .*')
                       as shape
                  from pg_index i
                 where i.indrelid = ?::regclass
                """, qualifiedTable).getValues("shape", String.class);
    }

    private static String upstreamDdl() throws IOException {
        try (InputStream in = ScheduledTasksSchemaIT.class.getClassLoader()
                .getResourceAsStream(UPSTREAM_DDL)) {
            assertThat(in).as("%s must be on the test class path", UPSTREAM_DDL).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String migrationText(String fileName) throws IOException {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            Path file = candidate.resolve("src/main/resources/db/migration").resolve(fileName);
            if (Files.isRegularFile(file)) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("migration not found: " + fileName);
    }

    private static String sha256(String text) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
