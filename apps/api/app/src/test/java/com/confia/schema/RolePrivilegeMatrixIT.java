package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.support.PostgresIntegrationTest;
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

    /** {@code pg_roles.rolsuper} and {@code rolbypassrls} for one role, read from the catalogue. */
    private RoleAttributes roleAttributesOf(String role) {
        var row = dsl.fetchOne("select rolsuper, rolbypassrls from pg_roles where rolname = ?",
                role);
        return new RoleAttributes(row.get("rolsuper", Boolean.class),
                row.get("rolbypassrls", Boolean.class));
    }

    /** {@code has_table_privilege(role, table, privilege)}, evaluated by PostgreSQL itself. */
    private boolean hasTablePrivilege(String role, String table, String privilege) {
        return dsl.fetchOne("select has_table_privilege(?, ?, ?) as has_priv", role, table,
                privilege).get("has_priv", Boolean.class);
    }

    private record RoleAttributes(boolean rolsuper, boolean rolbypassrls) {
    }
}
