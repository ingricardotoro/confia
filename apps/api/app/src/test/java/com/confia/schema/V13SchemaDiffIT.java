package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.schema.V9SchemaDiffIT.Difference;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * {@code V13} redefines the five purge functions of {@code V11} and {@code V12} to reject a null
 * {@code p_cutoff} or {@code p_limit}, and changes nothing else, and {@code V1} to {@code V12} were
 * not edited (background-jobs-with-db-scheduler design.md decision 16, "Argumentos nulos";
 * specs/build-integrity/spec.md, scenarios BI117 and BI118). The catalogue is read after {@code
 * V12} and after {@code V13} with the snapshot {@link V9SchemaDiffIT} already uses: the five
 * function bodies change and no definition is added or removed, so no table grant either. That
 * snapshot does not read the function header, so the owner, {@code SECURITY DEFINER}, the {@code
 * SET} clauses and the {@code EXECUTE} grants of the five are compared separately: {@code CREATE OR
 * REPLACE} must leave all of them as they were. The checksums of the twelve earlier migrations are
 * recorded below: a migration applied in production is never edited, so a number that moves means a
 * file was touched.
 */
class V13SchemaDiffIT {

    private static final List<String> PURGE_FUNCTIONS = List.of(
            "identity_purge_password_reset_tokens",
            "identity_purge_refresh_token_families",
            "shared_purge_idempotency_keys",
            "identity_purge_login_backoff",
            "identity_purge_mfa_totp_backoff");

    /** Flyway checksums of V1 to V12, recorded while those files were unchanged since their merge. */
    private static final Map<String, Integer> EARLIER_CHECKSUMS = earlierChecksums();

    @Test
    void v13ChangesExactlyTheBodiesOfTheFivePurgeFunctionsAndAddsOrRemovesNothing()
            throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("12")) {
            Map<String, String> afterV12 = V9SchemaDiffIT.snapshot(database);
            database.migrate("13");
            Map<String, String> afterV13 = V9SchemaDiffIT.snapshot(database);

            Difference difference = Difference.between(afterV12, afterV13);

            assertThat(difference.added()).as("V13 adds no function, table, column, index, "
                    + "trigger, policy or table grant").isEmpty();
            assertThat(difference.removed()).as("definitions V13 removes").isEmpty();
            assertThat(difference.changed()).as("V13 changes the five purge function bodies only")
                    .containsExactlyInAnyOrderElementsOf(PURGE_FUNCTIONS.stream()
                            .map(name -> "function:" + name).toList());
        }
    }

    @Test
    void v13KeepsTheOwnerTheDefinerTheSettingsAndTheExecuteGrantsOfTheFive()
            throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("12")) {
            Map<String, String> afterV12 = headers(database);
            database.migrate("13");
            Map<String, String> afterV13 = headers(database);

            assertThat(afterV12).as("non-vacuous: the five functions exist after V12")
                    .containsOnlyKeys(PURGE_FUNCTIONS);
            assertThat(afterV13).as("owner, SECURITY DEFINER, SET clauses and EXECUTE grants")
                    .containsExactlyEntriesOf(afterV12);
            assertThat(afterV13.values()).allSatisfy(header -> assertThat(header)
                    .startsWith("owner=confia_owner|definer=true|")
                    .contains("search_path=pg_catalog, public", "TimeZone=UTC",
                            "confia_admin_app=X/confia_owner")
                    .doesNotContain("{=X", ",=X"));
        }
    }

    @Test
    void v1ToV12WereNotEditedTheirChecksumsAreTheRecordedOnesAndTheHistoryValidates()
            throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("13");
                Connection owner = database.connectionAs("confia_owner");
                Statement statement = owner.createStatement();
                ResultSet history = statement.executeQuery("""
                        select version, checksum, success from flyway_schema_history
                         where version ~ '^[0-9]+$' and version::int <= 12
                         order by version::int
                        """)) {
            Map<String, Integer> actual = new LinkedHashMap<>();
            while (history.next()) {
                assertThat(history.getBoolean("success")).isTrue();
                actual.put(history.getString("version"), history.getInt("checksum"));
            }
            assertThat(actual).as("non-vacuous: twelve earlier migrations").hasSize(12)
                    .containsExactlyEntriesOf(EARLIER_CHECKSUMS);
            database.validateUpTo("13");
        }
    }

    /** Per purge function: owner, SECURITY DEFINER, SET clauses and access list, as text. */
    private static Map<String, String> headers(StepwiseDatabase database) throws SQLException {
        Map<String, String> headers = new TreeMap<>();
        try (Connection connection = database.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select p.proname,
                               'owner=' || pg_get_userbyid(p.proowner) || '|definer=' || p.prosecdef
                                   || '|config=' || coalesce(p.proconfig::text, '')
                                   || '|acl=' || coalesce(p.proacl::text, '')
                          from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                         where n.nspname = 'public' and p.proname = any (?)
                        """)) {
            statement.setArray(1, connection.createArrayOf("text", PURGE_FUNCTIONS.toArray()));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    headers.put(rows.getString(1), rows.getString(2));
                }
            }
        }
        return headers;
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
        return checksums;
    }
}
