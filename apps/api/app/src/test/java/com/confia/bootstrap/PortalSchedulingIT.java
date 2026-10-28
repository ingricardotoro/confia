package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import com.confia.shared.jobs.ActorKind;
import com.confia.shared.jobs.JobInstanceId;
import com.confia.shared.jobs.JobMeta;
import com.confia.shared.jobs.JobScheduler;
import com.confia.shared.jobs.JobType;
import com.confia.shared.jobs.RetryPolicy;
import com.confia.shared.jobs.ScheduleOutcome;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.PostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Specs/build-integrity of background-jobs-with-db-scheduler (BJ09, BI66; design.md decision 14,
 * D-N1 and decision 15): the real portal process, started by the production launcher against a real
 * PostgreSQL as {@code confia_portal_app}, schedules a task through the real {@link JobScheduler}
 * inside a {@link TransactionRunner} transaction, and the row is there. The same role cannot change
 * or delete a task: the grant of V10 is {@code SELECT} and {@code INSERT} only.
 */
class PortalSchedulingIT extends CommittingPostgresIntegrationTest {

    private record Probe(UUID accountId) { }

    private static final JobType<Probe> TYPE = new JobType<>("portal.scheduling-probe", Probe.class,
            new RetryPolicy(3, Duration.ofSeconds(30), 2.0, Duration.ofMinutes(5)));

    private static final String PORTAL_ROLE = "confia_portal_app";

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Test
    void thePortalSchedulesATaskThroughTheRealClientAndTheRowIsThere() throws SQLException {
        JobInstanceId instance = newInstance();

        ScheduleOutcome outcome = scheduleThroughThePortal(instance);

        assertThat(outcome).isEqualTo(ScheduleOutcome.SCHEDULED);
        assertThat(rowsOf(instance)).as("the row the portal inserted").isEqualTo(1);
    }

    @Test
    void thePortalRoleCannotUpdateOrDeleteATask() throws SQLException {
        JobInstanceId instance = newInstance();
        scheduleThroughThePortal(instance);

        try (Connection portal = SharedPostgresContainer.connectionAs(PORTAL_ROLE)) {
            assertThatThrownBy(() -> execute(portal, "update scheduled_tasks set priority = 1 "
                    + "where task_name = ?")).isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .as("insufficient_privilege on UPDATE").isEqualTo(INSUFFICIENT_PRIVILEGE);
            assertThatThrownBy(() -> execute(portal, "delete from scheduled_tasks "
                    + "where task_name = ?")).isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .as("insufficient_privilege on DELETE").isEqualTo(INSUFFICIENT_PRIVILEGE);
        }
        assertThat(rowsOf(instance)).as("the row survives both attempts").isEqualTo(1);
    }

    private static JobInstanceId newInstance() {
        return JobInstanceId.derive(TYPE.name(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    /** Starts the real portal as the portal role, schedules inside a transaction, and stops it. */
    private static ScheduleOutcome scheduleThroughThePortal(JobInstanceId instance) {
        UUID institution = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        ConfigurableApplicationContext context = startPortalAs(PORTAL_ROLE);
        try {
            JobScheduler scheduler = context.getBean(JobScheduler.class);
            return context.getBean(TransactionRunner.class).execute(
                    new SecurityContext("", "system", institution.toString(), request.toString()),
                    () -> scheduler.schedule(TYPE, instance, new Probe(UUID.randomUUID()),
                            new JobMeta(UUID.randomUUID(), request, null, ActorKind.STAFF, institution, null),
                            Instant.parse("2026-10-27T12:00:00Z")));
        } finally {
            context.close();
        }
    }

    private static ConfigurableApplicationContext startPortalAs(String role) {
        // Testcontainers' own URL carries loggerLevel=OFF; the real portal connects without it.
        String url = "jdbc:postgresql://" + SharedPostgresContainer.instance().getHost() + ":"
                + SharedPostgresContainer.instance().getMappedPort(5432) + "/confia_test";
        LaunchOutcome outcome = ConfiaApplication.launch(
                TestProcessArguments.portalAgainst(url, role, PostgresIntegrationTest.TEST_PASSWORD), "portal");
        assertThat(outcome.context()).as("the portal must start").isNotNull();
        return outcome.context();
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, TYPE.name());
            statement.executeUpdate();
        }
    }

    /** The rows of the instance, counted as the superuser, which sees what the portal role may not. */
    private static long rowsOf(JobInstanceId instance) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from scheduled_tasks where task_name = ? and task_instance = ?")) {
            statement.setString(1, TYPE.name());
            statement.setString(2, instance.value());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }
}
