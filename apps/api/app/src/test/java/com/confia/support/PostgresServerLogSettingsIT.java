package com.confia.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The PostgreSQL of the integration tests is started as production must be (docs/05-infraestructura-y-
 * despliegue.md, dated note of 2026-11-11): the server log does not repeat the values of a failed row. A unique
 * violation is provoked and the log of the container is read to prove that the key values never reached it.
 */
class PostgresServerLogSettingsIT {

    @Test
    void theServerLogsTersePanicOnlyStatementsAndNoStatementText() throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                Statement statement = connection.createStatement()) {
            assertThat(show(statement, "log_error_verbosity")).isEqualTo("terse");
            assertThat(show(statement, "log_statement")).isEqualTo("none");
            assertThat(show(statement, "log_min_error_statement")).isEqualTo("panic");
        }
    }

    @Test
    void aUniqueViolationDoesNotWriteItsKeyValuesToTheServerLog() throws SQLException {
        String marker = "marker-" + UUID.randomUUID();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                Statement statement = connection.createStatement()) {
            statement.execute("create temp table log_probe (value text primary key)");
            statement.execute("insert into log_probe values ('" + marker + "')");
            try {
                statement.execute("insert into log_probe values ('" + marker + "')");
                throw new AssertionError("the duplicate was accepted");
            } catch (SQLException expected) {
                assertThat(expected.getSQLState()).isEqualTo("23505");
            }
        }

        assertThat(SharedPostgresContainer.instance().getLogs()).doesNotContain(marker);
    }

    private static String show(Statement statement, String setting) throws SQLException {
        try (ResultSet result = statement.executeQuery("show " + setting)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
