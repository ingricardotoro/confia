package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.schema.V9SchemaDiffIT.Difference;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Scenario BI64 (background-jobs-with-db-scheduler specs/build-integrity/spec.md): {@code V10}
 * creates {@code scheduled_tasks} with its indexes and privileges and nothing else, and {@code V1}
 * to {@code V9} were not edited. The catalogue is read after {@code V9} and after {@code V10} with
 * the snapshot {@link V9SchemaDiffIT} already uses; every added definition must belong to the new
 * table, and nothing may be removed or changed. The checksums of the nine earlier migrations are
 * recorded below: a migration applied in production is never edited, so a number that moves means
 * a file was touched.
 */
class V10SchemaDiffIT {

    /** Flyway checksums of V1 to V9, recorded while those files were unchanged since their merge. */
    private static final Map<String, Integer> EARLIER_CHECKSUMS = earlierChecksums();

    @Test
    void v10AddsOnlyTheScheduledTasksTableItsIndexesAndItsPrivileges() throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("9")) {
            Map<String, String> afterV9 = V9SchemaDiffIT.snapshot(database);
            database.migrate("10");
            Map<String, String> afterV10 = V9SchemaDiffIT.snapshot(database);

            Difference difference = Difference.between(afterV9, afterV10);

            assertThat(difference.removed()).as("definitions V10 removes").isEmpty();
            assertThat(difference.changed()).as("definitions V10 changes").isEmpty();
            assertThat(difference.added()).as("non-vacuous: V10 adds the table")
                    .contains("relation:scheduled_tasks")
                    .anyMatch(key -> key.startsWith("column:scheduled_tasks."))
                    .anyMatch(key -> key.startsWith("index:scheduled_tasks."))
                    .anyMatch(key -> key.startsWith("table-grant:scheduled_tasks."));
            assertThat(difference.added()).as("everything V10 adds belongs to scheduled_tasks")
                    .allMatch(key -> key.contains("scheduled_tasks"));
        }
    }

    @Test
    void theComparisonSeesAnAddedFunctionAndAnAddedTable() throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("10")) {
            Map<String, String> before = V9SchemaDiffIT.snapshot(database);
            try (Connection owner = database.connectionAs("confia_owner");
                    Statement statement = owner.createStatement()) {
                statement.execute("CREATE TABLE unrelated_probe (id integer)");
                statement.execute("CREATE FUNCTION unrelated_fn() RETURNS integer "
                        + "LANGUAGE sql AS 'select 1'");
            }

            Difference difference = Difference.between(before, V9SchemaDiffIT.snapshot(database));

            assertThat(difference.added()).anyMatch(key -> key.contains("unrelated_probe"))
                    .anyMatch(key -> key.contains("unrelated_fn"))
                    .as("the rule 'everything added belongs to scheduled_tasks' would fail")
                    .anyMatch(key -> !key.contains("scheduled_tasks"));
        }
    }

    @Test
    void v1ToV9WereNotEditedTheirChecksumsAreTheRecordedOnesAndTheHistoryValidates()
            throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("10");
                Connection owner = database.connectionAs("confia_owner");
                Statement statement = owner.createStatement();
                ResultSet history = statement.executeQuery("""
                        select version, checksum, success from flyway_schema_history
                         where version ~ '^[0-9]+$' and version::int <= 9
                         order by version::int
                        """)) {
            Map<String, Integer> actual = new LinkedHashMap<>();
            while (history.next()) {
                assertThat(history.getBoolean("success")).isTrue();
                actual.put(history.getString("version"), history.getInt("checksum"));
            }
            assertThat(actual).as("non-vacuous: nine earlier migrations").hasSize(9)
                    .containsExactlyEntriesOf(EARLIER_CHECKSUMS);
            database.validateUpTo("10");
        }
    }

    private static Map<String, Integer> earlierChecksums() {
        Map<String, Integer> checksums = new LinkedHashMap<>();
        checksums.put("1", 1656542624);
        checksums.put("2", 1945509423);
        checksums.put("3", 995467718);
        checksums.put("4", -661849325);
        checksums.put("5", 1597533721);
        checksums.put("6", 1863707966);
        checksums.put("7", 1997547506);
        checksums.put("8", 1681600631);
        checksums.put("9", -1193943126);
        return checksums;
    }
}
