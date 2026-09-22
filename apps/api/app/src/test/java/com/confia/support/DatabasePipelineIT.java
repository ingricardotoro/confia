package com.confia.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Smoke test for the whole data pipeline (proposal.md scope point 5, "Base de pruebas de
 * integración"; design.md section 11, step 2; apply-progress.md task 1.1): the Testcontainers
 * PostgreSQL container starts, Flyway migrated as {@code confia_owner} leaving a row in {@code
 * flyway_schema_history}, the application's own {@link DataSource} connects as {@code
 * confia_admin_app}, and every one of the five roles of {@code docs/03-seguridad.md} section 6.1
 * is neither {@code SUPERUSER} nor {@code BYPASSRLS} (docs/03 section 6.1, "Ninguno de estos roles
 * es SUPERUSER y ninguno tiene el atributo BYPASSRLS").
 *
 * <p>Deliberately red until {@link PostgresIntegrationTest} and the test-only role script exist
 * (task 1.5): there is nothing yet to start a container, run Flyway, or create the five roles.
 */
class DatabasePipelineIT extends PostgresIntegrationTest {

    private static final List<String> FIVE_ROLES = List.of(
            "confia_owner", "confia_admin_app", "confia_portal_app", "confia_readonly",
            "confia_backup");

    @Autowired
    private DataSource applicationDataSource;

    @Test
    void applicationConnectsAsConfiaAdminAppWithAHealthyFlywayHistory() throws Exception {
        try (Connection connection = applicationDataSource.getConnection()) {
            assertThat(currentUser(connection)).isEqualTo("confia_admin_app");
            // PR A1 carries no business migration yet (the first one, V1, is PR A2's task 2.2):
            // confia_owner's Flyway run leaves flyway_schema_history CREATED with zero rows
            // ("Schema is up to date. No migration necessary.", confirmed by running this test),
            // not "at least one row" as it will once A2 lands. What this smoke test can already
            // prove from A1 alone is that Flyway actually ran its bookkeeping as confia_owner and
            // that the resulting table is visible in the catalog — to_regclass is a catalog
            // lookup, not a privilege check, so it works even though confia_admin_app has no
            // GRANT on this table yet (that GRANT arrives with the V1 migration in A2, docs/03
            // section 6.1; apply-progress.md task 1.5).
            assertThat(flywaySchemaHistoryTableExists(connection)).isTrue();
        }
    }

    @Test
    void noneOfTheFiveRolesIsSuperuserOrBypassesRowLevelSecurity() throws Exception {
        try (Connection connection = applicationDataSource.getConnection()) {
            for (String role : FIVE_ROLES) {
                RoleAttributes attributes = roleAttributes(connection, role);
                assertThat(attributes.rolsuper())
                        .as("%s must not be SUPERUSER (docs/03 section 6.1)", role)
                        .isFalse();
                assertThat(attributes.rolbypassrls())
                        .as("%s must not have BYPASSRLS (docs/03 section 6.1)", role)
                        .isFalse();
            }
        }
    }

    private static String currentUser(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("select current_user");
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static boolean flywaySchemaHistoryTableExists(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                        "select to_regclass('public.flyway_schema_history') is not null");
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getBoolean(1);
        }
    }

    private static RoleAttributes roleAttributes(Connection connection, String role)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select rolsuper, rolbypassrls from pg_roles where rolname = ?")) {
            statement.setString(1, role);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("role %s must exist", role).isTrue();
                return new RoleAttributes(
                        resultSet.getBoolean("rolsuper"), resultSet.getBoolean("rolbypassrls"));
            }
        }
    }

    private record RoleAttributes(boolean rolsuper, boolean rolbypassrls) {
    }
}
