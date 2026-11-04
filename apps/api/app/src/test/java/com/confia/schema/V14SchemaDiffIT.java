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
 * {@code V14} adds the role-assignment table and nothing else, and {@code V1} to {@code V13} were not
 * edited (rbac-permission-matrix-and-audit-integration design.md D4; specs/build-integrity/spec.md,
 * scenario BI120). The catalogue is read after {@code V13} and after {@code V14} with the snapshot
 * {@link V9SchemaDiffIT} already uses: everything that appears is part of the new table, nothing
 * disappears or changes, and no table of roles or permissions beyond it exists. The checksums of the
 * thirteen earlier migrations are recorded below: a migration applied in production is never edited,
 * so a number that moves means a file was touched.
 */
class V14SchemaDiffIT {

    private static final String TABLE = "identity_staff_account_role";

    /** Flyway checksums of V1 to V13, recorded while those files were unchanged since their merge. */
    private static final Map<String, Integer> EARLIER_CHECKSUMS = earlierChecksums();

    @Test
    void v14AddsOnlyTheAssignmentTableItsIndexesConstraintsPolicyAndPrivileges()
            throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("13")) {
            Map<String, String> afterV13 = V9SchemaDiffIT.snapshot(database);
            database.migrate("14");
            Map<String, String> afterV14 = V9SchemaDiffIT.snapshot(database);

            Difference difference = Difference.between(afterV13, afterV14);

            assertThat(difference.removed()).as("definitions V14 removes").isEmpty();
            assertThat(difference.changed()).as("definitions V14 changes").isEmpty();
            assertThat(difference.added()).as("every addition belongs to the new table")
                    .isNotEmpty().allSatisfy(key -> assertThat(key).contains(TABLE));
            assertThat(difference.added()).contains("relation:" + TABLE,
                    "column:" + TABLE + ".institution_id", "column:" + TABLE + ".account_id",
                    "column:" + TABLE + ".role_code", "column:" + TABLE + ".created_at",
                    "index:" + TABLE + "." + TABLE + "_pk",
                    "constraint:" + TABLE + "." + TABLE + "_pk",
                    "constraint:" + TABLE + "." + TABLE + "_account_fk",
                    "constraint:" + TABLE + "." + TABLE + "_code_chk",
                    "policy:" + TABLE + "." + TABLE + "_institution_isolation");
            assertThat(afterV14.get("relation:" + TABLE)).as("row security enabled and forced")
                    .isEqualTo("r|rls=true|force=true");
            assertThat(difference.added()).filteredOn(key -> key.startsWith("relation:"))
                    .as("one relation: no global table of roles or permissions")
                    .containsExactly("relation:" + TABLE);
            assertThat(difference.added()).filteredOn(key -> key.startsWith("table-grant:")
                    && !key.contains(".confia_owner.") && !key.contains(".postgres."))
                    .as("the privileges of the application roles on the new table")
                    .containsExactlyInAnyOrder("table-grant:" + TABLE + ".confia_admin_app.SELECT",
                            "table-grant:" + TABLE + ".confia_readonly.SELECT");
        }
    }

    @Test
    void v1ToV13WereNotEditedTheirChecksumsAreTheRecordedOnesAndTheHistoryValidates()
            throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("14");
                Connection owner = database.connectionAs("confia_owner");
                Statement statement = owner.createStatement();
                ResultSet history = statement.executeQuery("""
                        select version, checksum, success from flyway_schema_history
                         where version ~ '^[0-9]+$' and version::int <= 13
                         order by version::int
                        """)) {
            Map<String, Integer> actual = new LinkedHashMap<>();
            while (history.next()) {
                assertThat(history.getBoolean("success")).isTrue();
                actual.put(history.getString("version"), history.getInt("checksum"));
            }
            assertThat(actual).as("non-vacuous: thirteen earlier migrations").hasSize(13)
                    .containsExactlyEntriesOf(EARLIER_CHECKSUMS);
            database.validateUpTo("14");
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
        return checksums;
    }
}
