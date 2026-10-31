package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.jobs.PurgeProbeRows;
import com.confia.shared.jobs.WorkerHarness;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * The four purges of identity registered in the real worker (scenarios I262 and I255 of identity,
 * background-jobs-with-db-scheduler design.md decisions 10 and 17): when the daily cycle of each comes
 * due, it runs once for the configured institution with the {@code system} actor and the occurrence's
 * run id as request, and the data of an institution that is not configured is not touched.
 */
class IdentityPurgeJobsOnWorkerIT extends IdentityPurgeFixtures {

    private static final String[] IDENTITY_TASKS = {PurgeExpiredPasswordResetTokens.TASK_NAME,
            PurgeExpiredRefreshTokens.TASK_NAME, "identity.purge-login-backoff",
            "identity.purge-mfa-totp-backoff"};

    /** The worker adds nothing of its own to the production registration. */
    @Configuration(proxyBeanMethods = false)
    static class NoFixtures {
    }

    @Test
    void theFourPurgesRunOnceForTheConfiguredInstitutionAsTheSystemAndNotForAnother() {
        Instant now = databaseNow();
        Instant expired = now.minus(Duration.ofDays(2));
        UUID mine = seedPlainAccount(institutionId);
        UUID foreign = seedPlainAccount(otherInstitution);
        seedResetTokens(institutionId, mine, 3, expired);
        seedResetTokens(otherInstitution, foreign, 2, expired);
        UUID myConsumed = seedConsumedWithResidualSuccessor(institutionId, now.minus(Duration.ofHours(1)));
        UUID foreignConsumed = seedConsumedWithResidualSuccessor(otherInstitution, now.minus(Duration.ofHours(1)));
        Instant idle = now.minus(Duration.ofDays(2));
        for (UUID institution : List.of(institutionId.value(), otherInstitution.value())) {
            PurgeProbeRows.loginBackoff(institution, 4, idle);
            PurgeProbeRows.mfaTotpBackoff(institution, 2, idle);
        }

        try (ConfigurableApplicationContext worker = WorkerHarness.start(NoFixtures.class, institutionId.value())) {
            awaitTrue(() -> scheduledPurges() == 4);
            dueNow();
            awaitTrue(() -> !entries(institutionId, PurgeExpiredPasswordResetTokens.ACTION).isEmpty()
                    && !entries(institutionId, PurgeExpiredRefreshTokens.ACTION_PURGED).isEmpty()
                    && !entries(institutionId, "identity.login_backoff.purged").isEmpty()
                    && !entries(institutionId, "identity.mfa_totp_backoff.purged").isEmpty());
        }

        assertThat(count(institutionId, "identity_password_reset_token")).isZero();
        assertThat(count(otherInstitution, "identity_password_reset_token")).isEqualTo(2);
        assertThat(PurgeProbeRows.count("identity_login_backoff", institutionId.value())).isZero();
        assertThat(PurgeProbeRows.count("identity_mfa_totp_backoff", institutionId.value())).isZero();
        assertThat(PurgeProbeRows.count("identity_login_backoff", otherInstitution.value())).isEqualTo(4);
        assertThat(PurgeProbeRows.count("identity_mfa_totp_backoff", otherInstitution.value())).isEqualTo(2);
        assertThat(residualSuccessorOf(institutionId, myConsumed)).isNull();
        assertThat(residualSuccessorOf(otherInstitution, foreignConsumed)).isEqualTo("v1:AAAA");
        assertThat(entries(institutionId, PurgeExpiredPasswordResetTokens.ACTION)).as("once").hasSize(1);
        assertThat(entries(institutionId, PurgeExpiredRefreshTokens.ACTION_PURGED)).as("once").hasSize(1);
        AuditRowSnapshot reset = entries(institutionId, PurgeExpiredPasswordResetTokens.ACTION).get(0);
        AuditRowSnapshot cleared = entries(institutionId, PurgeExpiredRefreshTokens.ACTION_CLEARED).get(0);
        AuditRowSnapshot families = entries(institutionId, PurgeExpiredRefreshTokens.ACTION_PURGED).get(0);
        AuditRowSnapshot login = entries(institutionId, "identity.login_backoff.purged").get(0);
        AuditRowSnapshot totp = entries(institutionId, "identity.mfa_totp_backoff.purged").get(0);
        assertThat(detail(login).get("purged").asLong()).isEqualTo(4L);
        assertThat(detail(totp).get("purged").asLong()).isEqualTo(2L);
        assertThat(detail(reset).get("purged").asLong()).isEqualTo(3L);
        assertThat(detail(cleared).get("cleared").asLong()).isEqualTo(1L);
        assertThat(List.of(reset, cleared, families, login, totp)).allSatisfy(entry -> {
            assertThat(entry.actorKind()).isEqualTo("system");
            assertThat(entry.entityId()).isEqualTo(entry.requestId().toString());
        });
        assertThat(cleared.requestId()).as("one occurrence").isEqualTo(families.requestId());
        assertThat(reset.requestId()).as("another job, another occurrence").isNotEqualTo(families.requestId());
        assertThat(login.requestId()).isNotIn(reset.requestId(), families.requestId(), totp.requestId());
        assertThat(anyEntryOf(otherInstitution)).as("nothing is written for the other institution").isEmpty();
    }

    private List<String> anyEntryOf(InstitutionId institution) {
        return List.of(PurgeExpiredPasswordResetTokens.ACTION, PurgeExpiredRefreshTokens.ACTION_CLEARED,
                PurgeExpiredRefreshTokens.ACTION_PURGED, "identity.login_backoff.purged",
                "identity.mfa_totp_backoff.purged").stream()
                .flatMap(action -> entries(institution, action).stream()).map(AuditRowSnapshot::action).toList();
    }

    private static long scheduledPurges() {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from scheduled_tasks where task_name = any (?)")) {
            statement.setArray(1, connection.createArrayOf("text", IDENTITY_TASKS));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Moves the next occurrence of both purges into the past, so the next poll takes them. */
    private static void dueNow() {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "update scheduled_tasks set execution_time = now() - interval '1 second' "
                                + "where task_name = any (?) and picked = false")) {
            statement.setArray(1, connection.createArrayOf("text", IDENTITY_TASKS));
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
