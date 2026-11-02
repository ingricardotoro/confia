package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.confia.shared.jobs.infrastructure.JobCalendars;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Scenarios I262 and BJ62 of background-jobs-with-db-scheduler (design.md decisions 7 and 17), for the
 * recurring jobs the worker registers so far: the five purges, with the names and the daily times written
 * in the change, and the exact next execution of each from the fixed reference instant (Sunday
 * 2026-10-25 at 00:00 in Tegucigalpa, 06:00 UTC), none of them a year away or more, so none reaches the
 * parking instant. That they run for the configured institution is proved against a real database by
 * {@code IdentityPurgeJobsOnWorkerIT} and {@code RecurringJobPerInstitutionIT}.
 */
class RecurringJobCatalogTest {

    private static final Instant SUNDAY_MIDNIGHT = Instant.parse("2026-10-25T06:00:00Z");

    /** The five purges: the name and the daily time written in the change, and the instant of the first run (UTC). */
    private static final Map<String, LocalTime> PURGE_TIMES = Map.of(
            "identity.purge-password-reset-tokens", LocalTime.of(4, 30),
            "identity.purge-refresh-tokens", LocalTime.of(4, 35),
            "shared.purge-idempotency-keys", LocalTime.of(4, 40),
            "identity.purge-login-backoff", LocalTime.of(4, 45),
            "identity.purge-mfa-totp-backoff", LocalTime.of(4, 50));

    private static List<RecurringJob> purgeJobs() {
        return RegisteredJobs.ofTheWorker().stream().filter(RecurringJob.class::isInstance)
                .map(RecurringJob.class::cast).filter(job -> job.name().contains(".purge-")).toList();
    }

    @Test
    void theWorkerRegistersExactlyTheFivePurgesAtTheirDailyTimes() {
        assertThat(purgeJobs()).extracting(RecurringJob::name, RecurringJob::calendar)
                .containsExactlyInAnyOrder(
                        tuple("identity.purge-password-reset-tokens", new JobCalendar.DailyAt(LocalTime.of(4, 30))),
                        tuple("identity.purge-refresh-tokens", new JobCalendar.DailyAt(LocalTime.of(4, 35))),
                        tuple("shared.purge-idempotency-keys", new JobCalendar.DailyAt(LocalTime.of(4, 40))),
                        tuple("identity.purge-login-backoff", new JobCalendar.DailyAt(LocalTime.of(4, 45))),
                        tuple("identity.purge-mfa-totp-backoff", new JobCalendar.DailyAt(LocalTime.of(4, 50))));
    }

    /** BJ62: the observation of the queue is the eighth recurring job, 60 seconds after the reference instant. */
    @Test
    void theQueueObservationRunsSixtySecondsAfterTheReferenceInstantAndTheWorkerHasEightRecurringJobs() {
        List<RecurringJob> recurring = RegisteredJobs.ofTheWorker().stream()
                .filter(RecurringJob.class::isInstance).map(RecurringJob.class::cast).toList();

        assertThat(recurring).as("the catalog of eight").hasSize(8);
        RecurringJob observation = recurring.stream().filter(job -> job.name().equals("shared.queue-observation"))
                .findFirst().orElseThrow();
        assertThat(observation.calendar()).isEqualTo(new JobCalendar.Every(Duration.ofSeconds(60)));
        Instant next = JobCalendars.scheduleOf(observation.calendar())
                .getNextExecutionTime(ExecutionComplete.simulatedSuccess(SUNDAY_MIDNIGHT));
        assertThat(next).isEqualTo(Instant.parse("2026-10-25T06:01:00Z"));
        assertThat(Duration.between(SUNDAY_MIDNIGHT, next)).isLessThan(Duration.ofDays(365));
    }

    @Test
    void eachPurgeRunsAtItsExactNextInstantInTegucigalpaAndWithinTheYear() {
        assertThat(purgeJobs()).as("non-vacuous").hasSize(5).allSatisfy(job -> {
            Instant next = JobCalendars.scheduleOf(job.calendar())
                    .getNextExecutionTime(ExecutionComplete.simulatedSuccess(SUNDAY_MIDNIGHT));
            LocalTime time = PURGE_TIMES.get(job.name());
            assertThat(next).as(job.name()).isEqualTo(Instant.parse("2026-10-25T00:00:00Z")
                    .plus(Duration.ofHours(6)).plus(Duration.ofHours(time.getHour()))
                    .plus(Duration.ofMinutes(time.getMinute())));
            assertThat(Duration.between(SUNDAY_MIDNIGHT, next)).isLessThan(Duration.ofDays(365));
        });
    }
}
