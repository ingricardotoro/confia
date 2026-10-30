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
 * {@code V11} creates the two identity purge functions and nothing else, and {@code V1} to {@code
 * V10} were not edited (background-jobs-with-db-scheduler specs/build-integrity/spec.md). The
 * catalogue is read after {@code V10} and after {@code V11} with the snapshot {@link
 * V9SchemaDiffIT} already uses. The grants are not in that snapshot, so a function that also granted
 * {@code DELETE} on a table would show as an added {@code table-grant}: the test names none. The
 * checksums of the ten earlier migrations are recorded below: a migration applied in production is
 * never edited, so a number that moves means a file was touched.
 */
class V11SchemaDiffIT {

    /** Flyway checksums of V1 to V10, recorded while those files were unchanged since their merge. */
    private static final Map<String, Integer> EARLIER_CHECKSUMS = earlierChecksums();

    @Test
    void v11AddsOnlyTheTwoPurgeFunctions() throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("10")) {
            Map<String, String> afterV10 = V9SchemaDiffIT.snapshot(database);
            database.migrate("11");
            Map<String, String> afterV11 = V9SchemaDiffIT.snapshot(database);

            Difference difference = Difference.between(afterV10, afterV11);

            assertThat(difference.removed()).as("definitions V11 removes").isEmpty();
            assertThat(difference.changed()).as("definitions V11 changes").isEmpty();
            assertThat(difference.added()).as("V11 adds the two functions and no table, column, "
                    + "index, trigger, policy or table grant")
                    .containsExactlyInAnyOrder("function:identity_purge_password_reset_tokens",
                            "function:identity_purge_refresh_token_families");
        }
    }

    @Test
    void v1ToV10WereNotEditedTheirChecksumsAreTheRecordedOnesAndTheHistoryValidates()
            throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("11");
                Connection owner = database.connectionAs("confia_owner");
                Statement statement = owner.createStatement();
                ResultSet history = statement.executeQuery("""
                        select version, checksum, success from flyway_schema_history
                         where version ~ '^[0-9]+$' and version::int <= 10
                         order by version::int
                        """)) {
            Map<String, Integer> actual = new LinkedHashMap<>();
            while (history.next()) {
                assertThat(history.getBoolean("success")).isTrue();
                actual.put(history.getString("version"), history.getInt("checksum"));
            }
            assertThat(actual).as("non-vacuous: ten earlier migrations").hasSize(10)
                    .containsExactlyEntriesOf(EARLIER_CHECKSUMS);
            database.validateUpTo("11");
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
        checksums.put("10", -1061596103);
        return checksums;
    }
}
