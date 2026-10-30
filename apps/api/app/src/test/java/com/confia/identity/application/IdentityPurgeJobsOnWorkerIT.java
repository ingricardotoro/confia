package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.jobs.WorkerHarness;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * The purge registered in the real worker (scenarios I262 and I255 of identity,
 * background-jobs-with-db-scheduler design.md decisions 10 and 17): when its daily cycle comes due, it runs
 * once for the configured institution with the {@code system} actor and the occurrence's run id as
 * request, and the data of an institution that is not configured is not touched.
 */
class IdentityPurgeJobsOnWorkerIT extends IdentityPurgeFixtures {

    private static final String TASK = PurgeExpiredPasswordResetTokens.TASK_NAME;
    private static final String ACTION = PurgeExpiredPasswordResetTokens.ACTION;

    /** The worker adds nothing of its own to the production registration. */
    @Configuration(proxyBeanMethods = false)
    static class NoFixtures {
    }

    @Test
    void thePurgeRunsOnceForTheConfiguredInstitutionAsTheSystemAndNotForAnother() {
        Instant expired = databaseNow().minus(Duration.ofDays(2));
        UUID mine = seedPlainAccount(institutionId);
        UUID foreign = seedPlainAccount(otherInstitution);
        seedResetTokens(institutionId, mine, 3, expired);
        seedResetTokens(otherInstitution, foreign, 2, expired);

        try (ConfigurableApplicationContext worker = WorkerHarness.start(NoFixtures.class, institutionId.value())) {
            awaitTrue(() -> scheduledRows() == 1);
            dueNow();
            awaitTrue(() -> !entries(institutionId, ACTION).isEmpty());
        }

        assertThat(count(institutionId, "identity_password_reset_token")).isZero();
        assertThat(count(otherInstitution, "identity_password_reset_token")).isEqualTo(2);
        assertThat(entries(institutionId, ACTION)).as("once").singleElement().satisfies(entry -> {
            assertThat(detail(entry).get("purged").asLong()).isEqualTo(3L);
            assertThat(entry.actorKind()).isEqualTo("system");
            assertThat(entry.entityId()).isEqualTo(entry.requestId().toString());
        });
        assertThat(entries(otherInstitution, ACTION).stream().map(AuditRowSnapshot::action))
                .as("nothing is written for the other institution").isEmpty();
    }

    private static long scheduledRows() {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from scheduled_tasks where task_name = ?")) {
            statement.setString(1, TASK);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Moves the next occurrence of the purge into the past, so the next poll takes it. */
    private static void dueNow() {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "update scheduled_tasks set execution_time = now() - interval '1 second' "
                                + "where task_name = ? and picked = false")) {
            statement.setString(1, TASK);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A bounded wait for a condition, never a fixed pause. */
    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the condition did not hold within 90 seconds");
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
