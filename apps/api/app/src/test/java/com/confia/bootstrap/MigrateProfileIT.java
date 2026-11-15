package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The {@code migrate} process against a real PostgreSQL (scenarios CR03, CR04 and CR06 of
 * dockerfiles-and-local-compose, design.md decision 2): the production launcher, in this JVM, with
 * the credentials of the role that owns the database. Each test works in a database of its own, so
 * what the process did to the schema is exactly what the test reads back.
 *
 * <p>CR05 (unreachable database) has no database to read back and lives with the other launcher
 * cases in {@code ConfiaApplicationTest}.
 */
class MigrateProfileIT {

    private static final String BROKEN_LOCATIONS =
            "--spring.flyway.locations=classpath:db/migration,classpath:db/broken";

    @Test
    void appliesEveryMigrationOnAnEmptyDatabaseAsTheOwnerAndExitsWithZero() throws Exception {
        try (ScratchDatabase database = ScratchDatabase.ownedByTheRealOwner()) {
            Run run = migrate(database);

            assertThat(run.outcome().exitCode()).as(run.output()).isZero();
            assertThat(run.outcome().context()).as("no context is left alive").isNull();
            try (Connection connection = database.connectAsOwner()) {
                assertThat(history(connection)).as("non-vacuous: the migrations exist")
                        .hasSize(packagedMigrations()).allMatch(row -> row.endsWith("|true"));
                assertThat(scalar(connection, "SELECT count(*) FROM pg_tables WHERE schemaname = "
                        + "'public' AND tableowner <> 'confia_owner'"))
                        .as("every table belongs to confia_owner").isEqualTo("0");
                assertThat(scalar(connection, "SELECT count(*) FROM pg_tables WHERE schemaname = "
                        + "'public'")).as("non-vacuous: tables exist").isNotEqualTo("0");
                assertThat(scalar(connection, "SELECT count(*) FROM pg_proc p JOIN pg_namespace n "
                        + "ON n.oid = p.pronamespace JOIN pg_roles r ON r.oid = p.proowner "
                        + "WHERE n.nspname = 'public' AND r.rolname <> 'confia_owner'"))
                        .as("every function belongs to confia_owner").isEqualTo("0");
            }
        }
    }

    @Test
    void repeatingTheRunChangesNothingAndExitsWithZero() throws Exception {
        try (ScratchDatabase database = ScratchDatabase.ownedByTheRealOwner()) {
            assertThat(migrate(database).outcome().exitCode()).isZero();
            List<String> before;
            try (Connection connection = database.connectAsOwner()) {
                before = historyWithInstallTime(connection);
            }

            Run second = migrate(database);

            assertThat(second.outcome().exitCode()).as(second.output()).isZero();
            try (Connection connection = database.connectAsOwner()) {
                assertThat(historyWithInstallTime(connection)).isNotEmpty().isEqualTo(before);
            }
        }
    }

    @Test
    void aFailingMigrationExitsNonZeroNamesTheScriptAndNeverPrintsThePassword() throws Exception {
        try (ScratchDatabase database = ScratchDatabase.ownedByAThrowAwayRole()) {
            Run run = migrate(database, BROKEN_LOCATIONS);

            assertThat(run.outcome().exitCode()).isNotZero();
            assertThat(run.outcome().context()).isNull();
            assertThat(run.output()).as("the output names the migration that failed")
                    .contains("V999__fails.sql");
            assertThat(run.output()).as("the password of the connection never reaches the output")
                    .doesNotContain(database.ownerPassword());
        }
    }

    @Test
    void aDivergingChecksumExitsNonZeroNamesTheMigrationAndNeverPrintsThePassword()
            throws Exception {
        try (ScratchDatabase database = ScratchDatabase.ownedByAThrowAwayRole()) {
            assertThat(migrate(database).outcome().exitCode()).isZero();
            try (Connection connection = database.connectAsOwner();
                    Statement statement = connection.createStatement()) {
                assertThat(statement.executeUpdate("UPDATE flyway_schema_history "
                        + "SET checksum = checksum + 1 WHERE version = '1'"))
                        .as("non-vacuous: version 1 was applied").isEqualTo(1);
            }

            Run run = migrate(database);

            assertThat(run.outcome().exitCode()).isNotZero();
            assertThat(run.output()).as("the output identifies the migration")
                    .containsIgnoringCase("checksum mismatch").contains("version 1");
            assertThat(run.output()).doesNotContain(database.ownerPassword());
        }
    }

    private static Run migrate(ScratchDatabase database, String... extra) {
        List<String> arguments = new ArrayList<>(List.of(database.ownerArguments(
                "--logging.level.org.flywaydb=DEBUG",
                "--logging.level.com.zaxxer.hikari=DEBUG")));
        arguments.addAll(List.of(extra));
        return capturing(() -> ConfiaApplication.launch(arguments.toArray(String[]::new),
                "migrate"));
    }

    /** Runs {@code action} with the standard streams captured, which is where the logs go. */
    private static Run capturing(Supplier<LaunchOutcome> action) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream tee = new PrintStream(captured, true, StandardCharsets.UTF_8);
        System.setOut(tee);
        System.setErr(tee);
        LaunchOutcome outcome;
        try {
            outcome = action.get();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Run(outcome, captured.toString(StandardCharsets.UTF_8));
    }

    /** The version, description and success of every row of the history, in order. */
    private static List<String> history(Connection connection) throws SQLException {
        return rows(connection, "SELECT version || '|' || description || '|' || success "
                + "FROM flyway_schema_history ORDER BY installed_rank");
    }

    /** Every column of the history that would change if a migration ran again. */
    private static List<String> historyWithInstallTime(Connection connection) throws SQLException {
        return rows(connection, "SELECT installed_rank || '|' || coalesce(version, '') || '|' "
                + "|| checksum || '|' || installed_on || '|' || success "
                + "FROM flyway_schema_history ORDER BY installed_rank");
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        return rows(connection, sql).get(0);
    }

    /** How many versioned migrations the application packages. */
    private static int packagedMigrations() throws IOException {
        return new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V*.sql").length;
    }

    private record Run(LaunchOutcome outcome, String output) {
    }
}
