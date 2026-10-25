package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Probe P-6 and scenario BI66 (background-jobs-with-db-scheduler design.md decisions 14 and 15;
 * specs/build-integrity/spec.md): the real db-scheduler client, connected as {@code
 * confia_portal_app}, schedules with {@code scheduleIfNotExists} and with {@code schedule}, and the
 * same role cannot {@code UPDATE} or {@code DELETE} the table.
 *
 * <p><b>Finding recorded by this class (probe P-6).</b> {@code scheduleIfNotExists} reads the row
 * before it inserts, so with {@code INSERT} alone the portal role is refused with {@code permission
 * denied for table scheduled_tasks}; in the standard client {@code schedule} is implemented on top of
 * it (SchedulerClient.java, lines 441 and 447 of 16.12.0), so it is refused too. The scheduling port
 * uses {@code scheduleIfNotExists} anyway (a duplicate instance would otherwise abort the whole
 * business transaction, design.md decision 6), so {@code V10} grants {@code confia_portal_app}
 * {@code SELECT} as well. The consequence for readiness is recorded in {@code docs/07} section 9: the
 * grant exists for the client, and ADR-0017 grants no {@code SELECT} for health.
 */
class PortalSchedulingPrivilegeIT {

    private static final String PORTAL = "confia_portal_app";

    @Test
    void withInsertAloneNeitherClientCallCanSchedule() throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("10")) {
            try (Connection owner = database.connectionAs("confia_owner");
                    Statement statement = owner.createStatement()) {
                statement.execute("REVOKE SELECT ON scheduled_tasks FROM " + PORTAL);
            }
            SchedulerClient client =
                    SchedulerClient.Builder.create(database.dataSourceAs(PORTAL)).build();

            assertThatThrownBy(() -> client.schedule(
                    new TaskInstance<>("probe.portal-p6", "plain"), Instant.now()))
                    .as("schedule delegates to scheduleIfNotExists in the standard client")
                    .hasStackTraceContaining("permission denied for table scheduled_tasks");
            assertThatThrownBy(() -> client.scheduleIfNotExists(
                    new TaskInstance<>("probe.portal-p6", "if-not-exists"), Instant.now()))
                    .as("scheduleIfNotExists reads before it inserts")
                    .hasStackTraceContaining("permission denied for table scheduled_tasks");
            assertThat(rowsOf(database)).as("nothing was inserted").isZero();
        }
    }

    @Test
    void thePortalRoleSchedulesWithBothClientCallsAndCannotUpdateOrDelete() throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("10")) {
            SchedulerClient client =
                    SchedulerClient.Builder.create(database.dataSourceAs(PORTAL)).build();

            boolean scheduledIfAbsent = client.scheduleIfNotExists(
                    new TaskInstance<>("probe.portal-p6", "if-not-exists"), Instant.now());
            boolean secondTime = client.scheduleIfNotExists(
                    new TaskInstance<>("probe.portal-p6", "if-not-exists"), Instant.now());
            client.schedule(new TaskInstance<>("probe.portal-p6", "plain"), Instant.now());

            assertThat(scheduledIfAbsent).as("scheduleIfNotExists reports a new row").isTrue();
            assertThat(secondTime).as("the same instance again reports it already exists")
                    .isFalse();
            assertThat(rowsOf(database)).as("two rows: one per distinct instance").isEqualTo(2L);
            try (Connection portal = database.connectionAs(PORTAL);
                    Statement statement = portal.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate(
                        "UPDATE scheduled_tasks SET picked = true"))
                        .as("UPDATE is denied to the portal role")
                        .hasMessageContaining("permission denied for table scheduled_tasks");
                assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM scheduled_tasks"))
                        .as("DELETE is denied to the portal role")
                        .hasMessageContaining("permission denied for table scheduled_tasks");
            }
            assertThat(rowsOf(database)).as("the rows are still there").isEqualTo(2L);
        }
    }

    private static long rowsOf(StepwiseDatabase database) throws SQLException {
        try (Connection connection = database.connectionAs("postgres");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("select count(*) from scheduled_tasks")) {
            result.next();
            return result.getLong(1);
        }
    }
}
