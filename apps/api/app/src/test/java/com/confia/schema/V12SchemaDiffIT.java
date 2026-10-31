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
 * {@code V12} creates the three purge functions of idempotency keys and backoff rows and the two
 * indexes they scan by, and nothing else, and {@code V1} to {@code V11} were not edited
 * (background-jobs-with-db-scheduler design.md decision 16; specs/build-integrity/spec.md). The
 * catalogue is read after {@code V11} and after {@code V12} with the snapshot {@link V9SchemaDiffIT}
 * already uses. A function that also granted {@code DELETE} on a table would show as an added
 * {@code table-grant}: the test names none. The checksums of the eleven earlier migrations are
 * recorded below: a migration applied in production is never edited, so a number that moves means a
 * file was touched.
 */
class V12SchemaDiffIT {

    /** Flyway checksums of V1 to V11, recorded while those files were unchanged since their merge. */
    private static final Map<String, Integer> EARLIER_CHECKSUMS = earlierChecksums();

    @Test
    void v12AddsOnlyTheThreePurgeFunctionsAndTheirTwoIndexes() throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("11")) {
            Map<String, String> afterV11 = V9SchemaDiffIT.snapshot(database);
            database.migrate("12");
            Map<String, String> afterV12 = V9SchemaDiffIT.snapshot(database);

            Difference difference = Difference.between(afterV11, afterV12);

            assertThat(difference.removed()).as("definitions V12 removes").isEmpty();
            assertThat(difference.changed()).as("definitions V12 changes").isEmpty();
            assertThat(difference.added()).as("V12 adds three functions and two indexes, and no "
                    + "table, column, trigger, policy or table grant")
                    .containsExactlyInAnyOrder("function:shared_purge_idempotency_keys",
                            "function:identity_purge_login_backoff",
                            "function:identity_purge_mfa_totp_backoff",
                            "index:shared_idempotency_key.shared_idempotency_key_expires_idx",
                            "index:identity_login_backoff.identity_login_backoff_last_attempt_idx");
        }
    }

    @Test
    void v1ToV11WereNotEditedTheirChecksumsAreTheRecordedOnesAndTheHistoryValidates()
            throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("12");
                Connection owner = database.connectionAs("confia_owner");
                Statement statement = owner.createStatement();
                ResultSet history = statement.executeQuery("""
                        select version, checksum, success from flyway_schema_history
                         where version ~ '^[0-9]+$' and version::int <= 11
                         order by version::int
                        """)) {
            Map<String, Integer> actual = new LinkedHashMap<>();
            while (history.next()) {
                assertThat(history.getBoolean("success")).isTrue();
                actual.put(history.getString("version"), history.getInt("checksum"));
            }
            assertThat(actual).as("non-vacuous: eleven earlier migrations").hasSize(11)
                    .containsExactlyEntriesOf(EARLIER_CHECKSUMS);
            database.validateUpTo("12");
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
        checksums.put("11", -565218668);
        return checksums;
    }
}
