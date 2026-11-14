package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * No process other than {@code migrate} touches the schema (scenarios CR07 and CR08 of
 * dockerfiles-and-local-compose; docs/05: the deployed processes never migrate). These are
 * characterization tests: {@code application.yml} already says {@code spring.flyway.enabled:
 * false}, so they pass today and there is no natural red. Their red is the negative control:
 * turning that flag on makes both tests fail.
 *
 * <p>Each process connects as {@code confia_owner}, the one role that could create the schema if
 * anything tried, so a clean result cannot be put down to missing privileges. The "not ready"
 * half of CR07 for admin and portal arrives with the health probes of PR B.
 */
class OtherProfilesDoNotMigrateIT {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"admin", "portal", "worker"})
    void startingAgainstAnEmptyDatabaseCreatesNoTableAndNoHistory(String profile) throws Exception {
        try (ScratchDatabase database = ScratchDatabase.ownedByTheRealOwner()) {
            start(profile, database).close();

            try (Connection connection = database.connectAsOwner()) {
                assertThat(count(connection, "SELECT count(*) FROM pg_tables "
                        + "WHERE schemaname = 'public'")).as("tables in public").isZero();
                assertThat(count(connection, "SELECT count(*) FROM pg_tables "
                        + "WHERE tablename = 'flyway_schema_history'"))
                        .as("flyway_schema_history").isZero();
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"admin", "portal", "worker"})
    void startingAgainstADatabaseOneMigrationBehindLeavesTheHistoryUntouched(String profile)
            throws Exception {
        try (ScratchDatabase database = ScratchDatabase.ownedByTheRealOwner()) {
            int applied = migrateAllButTheLast(database);
            List<String> before = history(database);
            assertThat(before).as("non-vacuous: the seeded history").hasSize(applied)
                    .isNotEmpty();

            start(profile, database).close();

            assertThat(history(database)).isEqualTo(before);
        }
    }

    private static ConfigurableApplicationContext start(String profile, ScratchDatabase database) {
        String url = database.jdbcUrl();
        String user = database.ownerRole();
        String password = database.ownerPassword();
        String[] arguments = switch (profile) {
            case "admin" -> TestProcessArguments.adminWith(Map.of(
                    "spring.datasource.url", url, "spring.datasource.username", user,
                    "spring.datasource.password", password));
            case "portal" -> TestProcessArguments.portalAgainst(url, user, password);
            default -> TestProcessArguments.workerAgainst(url, user, password);
        };
        LaunchOutcome outcome = ConfiaApplication.launch(arguments, profile);
        assertThat(outcome.context()).as("the %s process must start", profile).isNotNull();
        return outcome.context();
    }

    /** Seeds the database with every packaged migration but the newest; returns how many. */
    private static int migrateAllButTheLast(ScratchDatabase database) {
        Flyway all = flyway(database, null);
        List<MigrationInfo> pending = List.of(all.info().all());
        assertThat(pending.size()).as("non-vacuous: at least two migrations").isGreaterThan(1);
        String target = pending.get(pending.size() - 2).getVersion().getVersion();
        Flyway behind = flyway(database, target);
        behind.migrate();
        return pending.size() - 1;
    }

    private static Flyway flyway(ScratchDatabase database, String target) {
        var configuration = Flyway.configure()
                .dataSource(database.jdbcUrl(), database.ownerRole(), database.ownerPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static List<String> history(ScratchDatabase database) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = database.connectAsOwner();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT installed_rank || '|' || "
                        + "coalesce(version, '') || '|' || checksum || '|' || installed_on || '|' "
                        + "|| success FROM flyway_schema_history ORDER BY installed_rank")) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
