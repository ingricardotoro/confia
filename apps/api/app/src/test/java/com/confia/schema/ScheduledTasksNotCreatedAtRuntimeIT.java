package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Scenario BI63 and probe P-9 (background-jobs-with-db-scheduler specs/build-integrity/spec.md;
 * design.md decision 15): the library never creates or alters its table, and the role the worker
 * and the administrative process connect with could not do it anyway, because it has no {@code
 * CREATE} on the schema. A database migrated only up to {@code V9} has no {@code scheduled_tasks};
 * a real {@link SchedulerClient} connected as {@code confia_admin_app} is started and asked to
 * schedule, which fails because the table does not exist, and afterwards no table exists and no
 * statement of the recorded text starts with {@code CREATE}, {@code ALTER} or {@code DROP}. A
 * fixture that creates the table the way a library with automatic creation would makes the same
 * check fail, so the check can fail.
 */
class ScheduledTasksNotCreatedAtRuntimeIT {

    private static final String UPSTREAM_DDL = "db/scheduler/postgresql_tables.sql";

    @Test
    void theRoleOfTheApplicationHasNoCreatePrivilegeOnTheSchema() throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("9");
                Connection connection = database.connectionAs("postgres");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        select has_schema_privilege('confia_admin_app', 'public', 'CREATE'),
                               has_schema_privilege('confia_portal_app', 'public', 'CREATE')
                        """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBoolean(1)).as("confia_admin_app CREATE on public").isFalse();
            assertThat(result.getBoolean(2)).as("confia_portal_app CREATE on public").isFalse();
        }
    }

    @Test
    void startingAndUsingTheClientAsTheApplicationRoleCreatesNoTable() throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("9")) {
            StatementRecordingDataSource recorder =
                    new StatementRecordingDataSource(database.dataSourceAs("confia_admin_app"));
            SchedulerClient client = SchedulerClient.Builder.create(recorder).build();

            assertThatThrownBy(() -> client.scheduleIfNotExists(
                    new TaskInstance<>("probe.never-created", "instance"), Instant.now()))
                    .as("scheduling must fail because the table is not there, not create it")
                    .hasStackTraceContaining("scheduled_tasks");

            assertNothingWasCreated(database, recorder);
            assertThat(recorder.statements()).as("non-vacuous: the client did talk to the database")
                    .isNotEmpty();
        }
    }

    @Test
    void aFixtureThatCreatesTheTableAtStartIsDetected() throws SQLException, IOException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("9")) {
            StatementRecordingDataSource recorder =
                    new StatementRecordingDataSource(database.dataSourceAs("confia_owner"));
            try (Connection connection = recorder.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute(upstreamDdl());
            }

            assertThatThrownBy(() -> assertNothingWasCreated(database, recorder))
                    .isInstanceOf(AssertionError.class);
        }
    }

    private static void assertNothingWasCreated(StepwiseDatabase database,
            StatementRecordingDataSource recorder) throws SQLException {
        try (Connection connection = database.connectionAs("postgres");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "select to_regclass('public.scheduled_tasks') is not null")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBoolean(1)).as("scheduled_tasks exists after the start").isFalse();
        }
        List<String> definitions = recorder.statements().stream()
                .map(sql -> sql.strip().toLowerCase(Locale.ROOT))
                .filter(sql -> sql.startsWith("create") || sql.startsWith("alter")
                        || sql.startsWith("drop"))
                .toList();
        assertThat(definitions).as("CREATE, ALTER or DROP statements emitted").isEmpty();
    }

    private static String upstreamDdl() throws IOException {
        try (var in = ScheduledTasksNotCreatedAtRuntimeIT.class.getClassLoader()
                .getResourceAsStream(UPSTREAM_DDL)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
