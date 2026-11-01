package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.jobs.infrastructure.JobCalendars;
import com.confia.support.AuditChainRows;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * The two cadences of the chain verifier on the real worker (background-jobs-with-db-scheduler design.md
 * decision 19; scenarios AT10, AT14, AT15 and AT16): they run once for each configured institution and for
 * no other, an alteration in one institution is not seen in another, the institution whose chain is broken
 * leaves the failed entry and the occurrence is recorded as failed, and the task stays scheduled for its
 * next occurrence a day or a week away instead of being retried.
 */
class AuditChainVerificationOnWorkerIT extends WorkerJobIT {

    private static final UUID CLEAN = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID BROKEN = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID NOT_CONFIGURED = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    private static final String DAILY = "shared.audit-chain-verification-daily";
    private static final String WEEKLY = "shared.audit-chain-verification-weekly";

    /** The worker adds nothing of its own to the production registration. */
    @Configuration(proxyBeanMethods = false)
    static class NoFixtures {
    }

    @BeforeEach
    @AfterEach
    void forgetTheTasks() {
        PurgeProbeRows.run("delete from scheduled_tasks where task_name in (?, ?)", DAILY, WEEKLY);
    }

    private static void seedWithAlteration(UUID institution, boolean altered) {
        Instant tenDaysAgo = Instant.now().minus(Duration.ofDays(10));
        AuditChainRows.insert(institution, tenDaysAgo.minusSeconds(2));
        long target = AuditChainRows.insert(institution, tenDaysAgo.minusSeconds(1));
        AuditChainRows.insert(institution, tenDaysAgo);
        if (altered) {
            AuditChainRows.alter(institution, target);
        }
    }

    private static List<String> entries(UUID institution, String action) {
        return PurgeProbeRows.auditDetails(institution, action);
    }

    private static long failures(String task) {
        return PurgeProbeRows.scalar("select consecutive_failures from scheduled_tasks where task_name = ?", task);
    }

    /** How far the next occurrence is from the one the calendar gives from now: zero for a rescheduled job, seconds for a retry. */
    private static Duration distanceToTheCalendar(String task, JobCalendar calendar) {
        Instant next = Instant.ofEpochSecond(PurgeProbeRows.scalar(
                "select extract(epoch from execution_time)::bigint from scheduled_tasks where task_name = ? "
                        + "and picked = false", task));
        Instant expected = JobCalendars.scheduleOf(calendar)
                .getNextExecutionTime(ExecutionComplete.simulatedSuccess(Instant.now()));
        return Duration.between(expected, next).abs();
    }

    @Test
    void bothCadencesRunForEachConfiguredInstitutionAndReportOnlyTheBrokenOneAsFailed() {
        seedWithAlteration(CLEAN, false);
        seedWithAlteration(BROKEN, true);
        seedWithAlteration(NOT_CONFIGURED, true);
        try (ConfigurableApplicationContext worker = WorkerHarness.startWith(NoFixtures.class,
                List.of(CLEAN, BROKEN))) {
            Eventually.within(Duration.ofSeconds(60), () -> assertThat(PurgeProbeRows.scalar(
                    "select count(*) from scheduled_tasks where task_name in (?, ?)", DAILY, WEEKLY)).isEqualTo(2));
            PurgeProbeRows.run("update scheduled_tasks set execution_time = now() - interval '1 second' "
                    + "where picked = false and task_name in (?, ?)", DAILY, WEEKLY);
            Eventually.within(Duration.ofSeconds(90), () -> {
                assertThat(failures(DAILY)).isEqualTo(1);
                assertThat(failures(WEEKLY)).isEqualTo(1);
                assertThat(distanceToTheCalendar(DAILY, new JobCalendar.DailyAt(LocalTime.of(3, 0))))
                        .as("the next day at 03:00, not a retry").isLessThan(Duration.ofMinutes(1));
                assertThat(distanceToTheCalendar(WEEKLY,
                        new JobCalendar.WeeklyAt(DayOfWeek.SUNDAY, LocalTime.of(3, 30))))
                        .as("the next Sunday at 03:30, not a retry").isLessThan(Duration.ofMinutes(1));
            });
        }

        assertThat(entries(CLEAN, "shared.audit.chain_verified")).containsExactlyInAnyOrder(
                "{\"scope\": \"daily\", \"verifiedRows\": 3}", "{\"scope\": \"weekly\", \"verifiedRows\": 3}");
        assertThat(entries(CLEAN, "shared.audit.chain_verification_failed")).isEmpty();
        assertThat(entries(BROKEN, "shared.audit.chain_verification_failed")).hasSize(2)
                .anySatisfy(entry -> assertThat(entry).contains("\"scope\": \"daily\""))
                .anySatisfy(entry -> assertThat(entry).contains("\"scope\": \"weekly\""));
        assertThat(entries(BROKEN, "shared.audit.chain_verified")).isEmpty();
        assertThat(entries(NOT_CONFIGURED, "shared.audit.chain_verification_failed")).isEmpty();
        assertThat(entries(NOT_CONFIGURED, "shared.audit.chain_verified")).isEmpty();
    }
}
