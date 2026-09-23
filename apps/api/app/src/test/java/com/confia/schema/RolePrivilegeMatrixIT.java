package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.support.PostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Role attribute and privilege-matrix gates over the real PostgreSQL roles and grants created in
 * PR A1 (roles) and PR A2 (grants) — design.md decision 10, points 5 and 6. Every assertion reads
 * {@code pg_roles} and {@code has_table_privilege} directly against the real database, never a
 * hand-maintained list.
 */
class RolePrivilegeMatrixIT extends PostgresIntegrationTest {

    private static final List<String> FIVE_ROLES = List.of("confia_owner", "confia_admin_app",
            "confia_portal_app", "confia_readonly", "confia_backup");

    /** Both tables PR B2a's V2 migration creates (design.md decisions 3 and 7). */
    private static final List<String> AUDIT_TABLES =
            List.of("shared_audit_log", "shared_audit_chain_head");

    private static final List<String> ALL_PRIVILEGES =
            List.of("SELECT", "INSERT", "UPDATE", "DELETE");

    @Test
    void noneOfTheFiveRolesIsSuperuserOrHasBypassRls() {
        for (String role : FIVE_ROLES) {
            RoleAttributes attributes = roleAttributesOf(role);
            assertThat(attributes.rolsuper()).as("%s must not be SUPERUSER", role).isFalse();
            assertThat(attributes.rolbypassrls()).as("%s must not have BYPASSRLS", role)
                    .isFalse();
        }
    }

    @Test
    void confiaAdminAppCanSelectInsertAndUpdateButNeverDeleteOnTheRootTable() {
        assertThat(hasTablePrivilege("confia_admin_app", "organization_institution", "SELECT"))
                .isTrue();
        assertThat(hasTablePrivilege("confia_admin_app", "organization_institution", "INSERT"))
                .isTrue();
        assertThat(hasTablePrivilege("confia_admin_app", "organization_institution", "UPDATE"))
                .isTrue();
        assertThat(hasTablePrivilege("confia_admin_app", "organization_institution", "DELETE"))
                .isFalse();
    }

    @Test
    void confiaPortalAppHasNoPrivilegeOnTheRootTableButCanSelectFlywayHistory() {
        assertThat(hasTablePrivilege("confia_portal_app", "organization_institution", "SELECT"))
                .isFalse();
        assertThat(hasTablePrivilege("confia_portal_app", "organization_institution", "INSERT"))
                .isFalse();
        assertThat(hasTablePrivilege("confia_portal_app", "organization_institution", "UPDATE"))
                .isFalse();
        assertThat(hasTablePrivilege("confia_portal_app", "organization_institution", "DELETE"))
                .isFalse();
        assertThat(hasTablePrivilege("confia_portal_app", "flyway_schema_history", "SELECT"))
                .isTrue();
    }

    @Test
    void confiaReadonlyOnlyHasSelectOnTheRootTable() {
        assertThat(hasTablePrivilege("confia_readonly", "organization_institution", "SELECT"))
                .isTrue();
        assertThat(hasTablePrivilege("confia_readonly", "organization_institution", "INSERT"))
                .isFalse();
        assertThat(hasTablePrivilege("confia_readonly", "organization_institution", "UPDATE"))
                .isFalse();
        assertThat(hasTablePrivilege("confia_readonly", "organization_institution", "DELETE"))
                .isFalse();
    }

    /**
     * {@code confia_owner} conserves every privilege on both audit tables by definition — it is
     * never subject to {@code GRANT}/{@code REVOKE}. This is deliberately not a defect: the
     * append-only trigger, not this matrix, is what rejects the owner's {@code UPDATE}/{@code
     * DELETE}/{@code TRUNCATE}, proven by a separate test ({@code AuditLogAppendOnlyIT}, design.md
     * decision 8).
     */
    @Test
    void confiaOwnerRetainsAllPrivilegesOnBothAuditTablesByDefinition() {
        for (String table : AUDIT_TABLES) {
            for (String privilege : ALL_PRIVILEGES) {
                assertThat(hasTablePrivilege("confia_owner", table, privilege))
                        .as("confia_owner must retain %s on %s by definition", privilege, table)
                        .isTrue();
            }
        }
    }

    @Test
    void confiaAdminAppCanSelectAndInsertOnSharedAuditLogButHasNoPrivilegeOnTheChainHead() {
        assertThat(hasTablePrivilege("confia_admin_app", "shared_audit_log", "SELECT")).isTrue();
        assertThat(hasTablePrivilege("confia_admin_app", "shared_audit_log", "INSERT")).isTrue();
        assertThat(hasTablePrivilege("confia_admin_app", "shared_audit_log", "UPDATE")).isFalse();
        assertThat(hasTablePrivilege("confia_admin_app", "shared_audit_log", "DELETE")).isFalse();

        for (String privilege : ALL_PRIVILEGES) {
            assertThat(hasTablePrivilege("confia_admin_app", "shared_audit_chain_head", privilege))
                    .as("confia_admin_app must have no privilege at all on "
                            + "shared_audit_chain_head; only the future SECURITY DEFINER trigger "
                            + "writes it (design.md decision 3)")
                    .isFalse();
        }
    }

    @Test
    void confiaPortalAppHasNoPrivilegeOnEitherAuditTable() {
        for (String table : AUDIT_TABLES) {
            for (String privilege : ALL_PRIVILEGES) {
                assertThat(hasTablePrivilege("confia_portal_app", table, privilege))
                        .as("confia_portal_app must have no privilege on %s", table).isFalse();
            }
        }
    }

    @Test
    void confiaReadonlyOnlySelectsSharedAuditLogAndHasNoPrivilegeOnTheChainHead() {
        assertThat(hasTablePrivilege("confia_readonly", "shared_audit_log", "SELECT")).isTrue();
        assertThat(hasTablePrivilege("confia_readonly", "shared_audit_log", "INSERT")).isFalse();
        assertThat(hasTablePrivilege("confia_readonly", "shared_audit_log", "UPDATE")).isFalse();
        assertThat(hasTablePrivilege("confia_readonly", "shared_audit_log", "DELETE")).isFalse();

        for (String privilege : ALL_PRIVILEGES) {
            assertThat(hasTablePrivilege("confia_readonly", "shared_audit_chain_head", privilege))
                    .isFalse();
        }
    }

    /** {@code confia_backup} reads through {@code pg_read_all_data}, which never bypasses RLS. */
    @Test
    void confiaBackupCanOnlySelectBothAuditTablesThroughPgReadAllData() {
        for (String table : AUDIT_TABLES) {
            assertThat(hasTablePrivilege("confia_backup", table, "SELECT")).isTrue();
            assertThat(hasTablePrivilege("confia_backup", table, "INSERT")).isFalse();
            assertThat(hasTablePrivilege("confia_backup", table, "UPDATE")).isFalse();
            assertThat(hasTablePrivilege("confia_backup", table, "DELETE")).isFalse();
        }
    }

    /** {@code pg_roles.rolsuper} and {@code rolbypassrls} for one role, read from the catalogue. */
    private RoleAttributes roleAttributesOf(String role) {
        var row = dsl.fetchOne("select rolsuper, rolbypassrls from pg_roles where rolname = ?",
                role);
        return new RoleAttributes(row.get("rolsuper", Boolean.class),
                row.get("rolbypassrls", Boolean.class));
    }

    /**
     * The scenario "{@code PUBLIC} no tiene ningún privilegio de partida"
     * (specs/audit-trail/spec.md, requirement "Permisos de acceso a {@code shared_audit_log} por
     * rol de base de datos"), which had no test until the SDD verification of this change found it
     * missing.
     *
     * <p>Asserting that {@code confia_portal_app} holds nothing does <b>not</b> cover this: that is
     * one named role with no {@code GRANT} of its own. {@code PUBLIC} is the pseudo-role every
     * other role inherits from, so a privilege granted to it would reach roles this matrix never
     * names — including ones created years from now. Both halves of the scenario are checked here:
     * the pseudo-role itself, which is what {@code REVOKE ALL ... FROM PUBLIC} acts on, and a
     * freshly created role that is none of the five, which is what the scenario's premise says.
     */
    @Test
    void publicAndAnyRoleOutsideTheFiveInheritNoPrivilegeOnEitherAuditTable() throws SQLException {
        for (String table : AUDIT_TABLES) {
            for (String privilege : ALL_PRIVILEGES) {
                assertThat(hasTablePrivilege("public", table, privilege))
                        .as("PUBLIC must hold no %s on %s: REVOKE ALL ... FROM PUBLIC is what "
                                + "keeps every present and future role from inheriting it",
                                privilege, table)
                        .isFalse();
            }
        }

        String scratchRole = "probe_role_outside_the_five";
        try (Connection superuser = SharedPostgresContainer.connectionAs("postgres");
                Statement statement = superuser.createStatement()) {
            statement.execute("drop role if exists " + scratchRole);
            statement.execute("create role " + scratchRole + " nosuperuser nobypassrls");
            try {
                for (String table : AUDIT_TABLES) {
                    for (String privilege : ALL_PRIVILEGES) {
                        assertThat(hasTablePrivilege(scratchRole, table, privilege))
                                .as("a role that is none of the five must inherit no %s on %s",
                                        privilege, table)
                                .isFalse();
                    }
                }
            } finally {
                statement.execute("drop role if exists " + scratchRole);
            }
        }
    }

    /** {@code has_table_privilege(role, table, privilege)}, evaluated by PostgreSQL itself. */
    private boolean hasTablePrivilege(String role, String table, String privilege) {
        return dsl.fetchOne("select has_table_privilege(?, ?, ?) as has_priv", role, table,
                privilege).get("has_priv", Boolean.class);
    }

    private record RoleAttributes(boolean rolsuper, boolean rolbypassrls) {
    }
}
