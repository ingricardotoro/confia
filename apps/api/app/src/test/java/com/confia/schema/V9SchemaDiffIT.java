package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Scenario BI41 (session-endpoints-cookie-and-csrf specs/build-integrity/spec.md; design.md decision
 * 16): {@code V9} changes the revocation reason constraint and nothing else, and {@code V8} was not
 * edited. Two database states are read from the catalogue, after {@code V8} and after {@code V9}, as
 * one flat map of named definitions each (relations and their row-security flags, columns, indexes,
 * constraints, triggers, policies, functions, table and column privileges); the difference must be
 * exactly the one constraint. A control that alters the {@code V8} state by hand shows the comparison
 * does see an added column, an added index and an added grant, so an empty difference means no
 * difference and not a blind comparison.
 */
class V9SchemaDiffIT {

    private static final String REASON_CONSTRAINT =
            "constraint:identity_refresh_token_family.identity_refresh_token_family_reason_chk";

    /**
     * The Flyway checksum of {@code V8__create_identity_refresh_token_tables.sql}, recorded when the
     * file was unchanged since its merge. A migration applied in production is never edited: if this
     * number moves, {@code V8} was touched.
     */
    private static final int V8_CHECKSUM = 1681600631;

    @Test
    void v9ChangesTheRevocationReasonConstraintAndNothingElse() throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("8")) {
            Map<String, String> afterV8 = snapshot(database);
            database.migrate("9");
            Map<String, String> afterV9 = snapshot(database);

            Difference difference = Difference.between(afterV8, afterV9);

            assertThat(difference.changed()).containsExactly(REASON_CONSTRAINT);
            assertThat(difference.added()).as("definitions V9 adds").isEmpty();
            assertThat(difference.removed()).as("definitions V9 removes").isEmpty();
            assertThat(afterV8.get(REASON_CONSTRAINT)).doesNotContain("logout", "mfa_change",
                    "binding_changed");
            assertThat(afterV9.get(REASON_CONSTRAINT)).contains("reuse_detected",
                    "password_change", "logout", "mfa_change", "binding_changed")
                    .doesNotContain("admin_revoke");
            assertThat(afterV9.get(REASON_CONSTRAINT)).startsWith("c|CHECK");
            assertThat(afterV9).as("the other revocation rule is still there")
                    .containsKey("constraint:identity_refresh_token_family"
                            + ".identity_refresh_token_family_revoked_chk");
        }
    }

    @Test
    void theComparisonSeesAnAddedColumnAnAddedIndexAndAnAddedGrant() throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("8")) {
            Map<String, String> before = snapshot(database);
            try (Connection owner = database.connectionAs("confia_owner");
                    Statement statement = owner.createStatement()) {
                statement.execute("ALTER TABLE identity_refresh_token_family "
                        + "ADD COLUMN device_label TEXT");
                statement.execute("CREATE INDEX probe_idx ON identity_refresh_token_family "
                        + "(institution_id, created_at)");
                statement.execute("GRANT UPDATE (absolute_expires_at) "
                        + "ON identity_refresh_token_family TO confia_admin_app");
            }

            Difference difference = Difference.between(before, snapshot(database));

            assertThat(difference.added()).anyMatch(key -> key.contains("device_label"))
                    .anyMatch(key -> key.contains("probe_idx"))
                    .anyMatch(key -> key.contains("absolute_expires_at")
                            && key.contains("confia_admin_app"));
            assertThat(difference.changed()).isEmpty();
        }
    }

    @Test
    void v8WasNotEditedItsChecksumInTheHistoryIsTheRecordedOneAndTheHistoryValidates()
            throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("9");
                Connection owner = database.connectionAs("confia_owner");
                Statement statement = owner.createStatement();
                ResultSet history = statement.executeQuery("""
                        select checksum, success from flyway_schema_history
                         where version = '8'
                        """)) {
            assertThat(history.next()).as("the history has a row for V8").isTrue();
            assertThat(history.getInt("checksum")).isEqualTo(V8_CHECKSUM);
            assertThat(history.getBoolean("success")).isTrue();
            database.validate();
        }
    }

    private static Map<String, String> snapshot(StepwiseDatabase database) throws SQLException {
        Map<String, String> definitions = new TreeMap<>();
        try (Connection connection = database.connectionAs("postgres");
                Statement statement = connection.createStatement()) {
            collect(statement, definitions, """
                    select 'relation:' || c.relname,
                           c.relkind::text || '|rls=' || c.relrowsecurity || '|force=' || c.relforcerowsecurity
                      from pg_class c join pg_namespace n on n.oid = c.relnamespace
                     where n.nspname = 'public' and c.relname <> 'flyway_schema_history'
                       and c.relkind in ('r', 'p', 'v', 'm', 'S')
                    """);
            collect(statement, definitions, """
                    select 'column:' || table_name || '.' || column_name,
                           data_type || '|' || is_nullable || '|' || coalesce(column_default, '')
                      from information_schema.columns
                     where table_schema = 'public' and table_name <> 'flyway_schema_history'
                    """);
            collect(statement, definitions, """
                    select 'index:' || tablename || '.' || indexname, indexdef
                      from pg_indexes
                     where schemaname = 'public' and tablename <> 'flyway_schema_history'
                    """);
            collect(statement, definitions, """
                    select 'constraint:' || cl.relname || '.' || c.conname,
                           c.contype::text || '|' || pg_get_constraintdef(c.oid)
                      from pg_constraint c join pg_class cl on cl.oid = c.conrelid
                      join pg_namespace n on n.oid = cl.relnamespace
                     where n.nspname = 'public' and cl.relname <> 'flyway_schema_history'
                    """);
            collect(statement, definitions, """
                    select 'trigger:' || cl.relname || '.' || t.tgname, pg_get_triggerdef(t.oid)
                      from pg_trigger t join pg_class cl on cl.oid = t.tgrelid
                      join pg_namespace n on n.oid = cl.relnamespace
                     where n.nspname = 'public' and not t.tgisinternal
                       and cl.relname <> 'flyway_schema_history'
                    """);
            collect(statement, definitions, """
                    select 'policy:' || tablename || '.' || policyname,
                           cmd || '|' || roles::text || '|' || coalesce(qual, '') || '|'
                               || coalesce(with_check, '')
                      from pg_policies where schemaname = 'public'
                    """);
            collect(statement, definitions, """
                    select 'function:' || p.proname, md5(p.prosrc)
                      from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                     where n.nspname = 'public'
                    """);
            collect(statement, definitions, """
                    select 'table-grant:' || c.relname || '.' || coalesce(r.rolname, 'PUBLIC')
                               || '.' || a.privilege_type, a.is_grantable::text
                      from pg_class c join pg_namespace n on n.oid = c.relnamespace,
                           lateral aclexplode(c.relacl) a
                      left join pg_roles r on r.oid = a.grantee
                     where n.nspname = 'public' and c.relname <> 'flyway_schema_history'
                       and c.relkind in ('r', 'p')
                    """);
            collect(statement, definitions, """
                    select 'column-grant:' || c.relname || '.' || at.attname || '.'
                               || coalesce(r.rolname, 'PUBLIC') || '.' || a.privilege_type,
                           a.is_grantable::text
                      from pg_attribute at join pg_class c on c.oid = at.attrelid
                      join pg_namespace n on n.oid = c.relnamespace,
                           lateral aclexplode(at.attacl) a
                      left join pg_roles r on r.oid = a.grantee
                     where n.nspname = 'public' and c.relname <> 'flyway_schema_history'
                       and at.attacl is not null
                    """);
        }
        return definitions;
    }

    private static void collect(Statement statement, Map<String, String> definitions, String query)
            throws SQLException {
        try (ResultSet rows = statement.executeQuery(query)) {
            while (rows.next()) {
                definitions.put(rows.getString(1), rows.getString(2));
            }
        }
    }

    private record Difference(Set<String> added, Set<String> removed, Set<String> changed) {

        static Difference between(Map<String, String> before, Map<String, String> after) {
            Set<String> added = new TreeSet<>(after.keySet());
            added.removeAll(before.keySet());
            Set<String> removed = new TreeSet<>(before.keySet());
            removed.removeAll(after.keySet());
            Set<String> changed = new TreeSet<>();
            before.forEach((key, definition) -> {
                if (after.containsKey(key) && !definition.equals(after.get(key))) {
                    changed.add(key);
                }
            });
            return new Difference(added, removed, changed);
        }
    }
}
