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
 * {@code V16} adds the email delivery log and nothing else, and {@code V1} to {@code V14} were not
 * edited (transactional-email-adapter design.md decision 7 and section 7; specs/build-integrity/spec.md,
 * requirements on {@code notifications_email_delivery}). The catalogue is read after {@code V14} and
 * after {@code V16} with the snapshot {@link V9SchemaDiffIT} already uses: everything that appears is
 * part of the new table, nothing disappears or changes. {@code V15} is reserved by another change and
 * is not in this tree; Flyway accepts the gap, and the target below names the versions that exist.
 *
 * <p>The checksums of the fourteen earlier migrations are recorded below: a migration applied in
 * production is never edited, so a number that moves means a file was touched.
 */
class V16SchemaDiffIT {

    private static final String TABLE = "notifications_email_delivery";

    /** Flyway checksums of V1 to V14, recorded while those files were unchanged since their merge. */
    private static final Map<String, Integer> EARLIER_CHECKSUMS = earlierChecksums();

    @Test
    void v16AddsOnlyTheDeliveryLogItsIndexConstraintsPolicyAndPrivileges() throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("14")) {
            Map<String, String> afterV14 = V9SchemaDiffIT.snapshot(database);
            database.migrate("16");
            Map<String, String> afterV16 = V9SchemaDiffIT.snapshot(database);

            Difference difference = Difference.between(afterV14, afterV16);

            assertThat(difference.removed()).as("definitions V16 removes").isEmpty();
            assertThat(difference.changed()).as("definitions V16 changes").isEmpty();
            assertThat(difference.added()).as("every addition belongs to the new table")
                    .isNotEmpty().allSatisfy(key -> assertThat(key).contains(TABLE));
            assertThat(difference.added()).contains("relation:" + TABLE,
                    "column:" + TABLE + ".institution_id", "column:" + TABLE + ".id",
                    "column:" + TABLE + ".request_id", "column:" + TABLE + ".attempted_at",
                    "column:" + TABLE + ".channel", "column:" + TABLE + ".purpose",
                    "column:" + TABLE + ".template_key", "column:" + TABLE + ".template_version",
                    "column:" + TABLE + ".recipient_hash", "column:" + TABLE + ".status",
                    "column:" + TABLE + ".provider_message_id", "column:" + TABLE + ".failure_class",
                    "index:" + TABLE + "." + TABLE + "_pk",
                    "index:" + TABLE + "." + TABLE + "_recipient_idx",
                    "constraint:" + TABLE + "." + TABLE + "_pk",
                    "constraint:" + TABLE + "." + TABLE + "_channel_chk",
                    "constraint:" + TABLE + "." + TABLE + "_purpose_chk",
                    "constraint:" + TABLE + "." + TABLE + "_template_key_chk",
                    "constraint:" + TABLE + "." + TABLE + "_template_version_chk",
                    "constraint:" + TABLE + "." + TABLE + "_recipient_hash_chk",
                    "constraint:" + TABLE + "." + TABLE + "_status_chk",
                    "constraint:" + TABLE + "." + TABLE + "_failure_class_chk",
                    "constraint:" + TABLE + "." + TABLE + "_outcome_chk",
                    "policy:" + TABLE + "." + TABLE + "_institution_isolation");
            assertThat(difference.added()).filteredOn(key -> key.startsWith("column:"))
                    .as("no column that could hold an address, a subject, a body or parameters")
                    .hasSize(12);
            assertThat(afterV16.get("relation:" + TABLE)).as("row security enabled and forced")
                    .isEqualTo("r|rls=true|force=true");
            assertThat(difference.added()).filteredOn(key -> key.startsWith("relation:"))
                    .containsExactly("relation:" + TABLE);
            assertThat(difference.added()).filteredOn(key -> key.startsWith("table-grant:")
                    && !key.contains(".confia_owner.") && !key.contains(".postgres."))
                    .as("the privileges of the application roles on the new table: insert-only")
                    .containsExactlyInAnyOrder("table-grant:" + TABLE + ".confia_admin_app.SELECT",
                            "table-grant:" + TABLE + ".confia_admin_app.INSERT",
                            "table-grant:" + TABLE + ".confia_readonly.SELECT");
            assertThat(difference.added()).filteredOn(key -> key.startsWith("column-grant:"))
                    .as("no column-level grant").isEmpty();
        }
    }

    @Test
    void v1ToV14WereNotEditedTheirChecksumsAreTheRecordedOnesAndTheHistoryValidates()
            throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("16");
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
            database.validateUpTo("16");
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
