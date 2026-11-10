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
 * {@code V15} drops the second factor flag column of {@code identity_staff_account} and nothing else,
 * and {@code V1} to {@code V14} were not edited (rbac-permission-matrix-and-audit-integration
 * design.md D15, step 2; specs/build-integrity/spec.md, scenario BI125). The catalogue is read after
 * {@code V14} and after {@code V15} with the snapshot {@link V9SchemaDiffIT} already uses: the only
 * definition that disappears is that column, nothing is added and nothing else changes. The
 * checksums of the fourteen earlier migrations are recorded below: a migration applied in production
 * is never edited, so a number that moves means a file was touched.
 */
class V15SchemaDiffIT {

    private static final String TABLE = "identity_staff_account";

    /** The retired column; this file migrates to a version before 15, so the absence test exempts it. */
    private static final String RETIRED_COLUMN = "mfa_required";

    /** Flyway checksums of V1 to V14, recorded while those files were unchanged since their merge. */
    private static final Map<String, Integer> EARLIER_CHECKSUMS = earlierChecksums();

    @Test
    void v15RemovesOnlyTheRetiredColumnOfTheStaffAccountAndAddsOrChangesNothing()
            throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("14")) {
            Map<String, String> afterV14 = V9SchemaDiffIT.snapshot(database);
            database.migrate("15");
            Map<String, String> afterV15 = V9SchemaDiffIT.snapshot(database);

            Difference difference = Difference.between(afterV14, afterV15);

            assertThat(afterV14).as("non-vacuous: the column exists after V14")
                    .containsKey("column:" + TABLE + "." + RETIRED_COLUMN);
            assertThat(difference.added()).as("V15 adds nothing").isEmpty();
            assertThat(difference.changed()).as("V15 changes no definition").isEmpty();
            // PostgreSQL 18 catalogues NOT NULL as a constraint of the column, and it goes with it.
            assertThat(difference.removed()).as("the column and its own NOT NULL constraint")
                    .containsExactlyInAnyOrder("column:" + TABLE + "." + RETIRED_COLUMN,
                            "constraint:" + TABLE + "." + TABLE + "_" + RETIRED_COLUMN + "_not_null");
        }
    }

    @Test
    void v1ToV14WereNotEditedTheirChecksumsAreTheRecordedOnesAndTheHistoryValidates()
            throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("15");
                Connection owner = database.connectionAs("confia_owner");
                Statement statement = owner.createStatement();
                ResultSet history = statement.executeQuery("""
                        select version, checksum, success from flyway_schema_history
                         where version ~ '^[0-9]+$' and version::int <= 14
                         order by version::int
                        """)) {
            Map<String, Integer> actual = new LinkedHashMap<>();
            while (history.next()) {
                assertThat(history.getBoolean("success")).isTrue();
                actual.put(history.getString("version"), history.getInt("checksum"));
            }
            assertThat(actual).as("non-vacuous: fourteen earlier migrations").hasSize(14)
                    .containsExactlyEntriesOf(EARLIER_CHECKSUMS);
            database.validateUpTo("15");
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
        checksums.put("12", -488638640);
        checksums.put("13", -1568951550);
        checksums.put("14", -279518595);
        return checksums;
    }
}
