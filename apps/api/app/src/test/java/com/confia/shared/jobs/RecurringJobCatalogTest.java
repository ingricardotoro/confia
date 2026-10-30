package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.confia.shared.jobs.infrastructure.JobCalendars;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Scenarios I262 and BJ62 of background-jobs-with-db-scheduler (design.md decisions 7 and 17), for the
 * recurring jobs the worker registers so far: the two identity purges, with the names and the daily
 * times written in the change, and the exact next execution of each from the fixed reference instant
 * (Sunday 2026-10-25 at 00:00 in Tegucigalpa, 06:00 UTC), none of them a year away or more, so none
 * reaches the parking instant. That they run for the configured institution is proved against a real
 * database by {@code IdentityPurgeJobsOnWorkerIT}.
 */
class RecurringJobCatalogTest {

    private static final Instant SUNDAY_MIDNIGHT = Instant.parse("2026-10-25T06:00:00Z");

    private static List<RecurringJob> identityJobs() {
        return RegisteredJobs.ofTheWorker().stream().filter(RecurringJob.class::isInstance)
                .map(RecurringJob.class::cast).filter(job -> job.name().startsWith("identity.")).toList();
    }

    @Test
    void theWorkerRegistersExactlyTheTwoIdentityPurgesAtTheirDailyTimes() {
        assertThat(identityJobs()).extracting(RecurringJob::name, RecurringJob::calendar)
                .containsExactlyInAnyOrder(
                        tuple("identity.purge-password-reset-tokens", new JobCalendar.DailyAt(LocalTime.of(4, 30))),
                        tuple("identity.purge-refresh-tokens", new JobCalendar.DailyAt(LocalTime.of(4, 35))));
    }

    @Test
    void eachPurgeRunsAtItsExactNextInstantInTegucigalpaAndWithinTheYear() {
        assertThat(identityJobs()).as("non-vacuous").hasSize(2).allSatisfy(job -> {
            Instant next = JobCalendars.scheduleOf(job.calendar())
                    .getNextExecutionTime(ExecutionComplete.simulatedSuccess(SUNDAY_MIDNIGHT));
            assertThat(next).isEqualTo(job.name().equals("identity.purge-password-reset-tokens")
                    ? Instant.parse("2026-10-25T10:30:00Z") : Instant.parse("2026-10-25T10:35:00Z"));
            assertThat(Duration.between(SUNDAY_MIDNIGHT, next)).isLessThan(Duration.ofDays(365));
        });
    }
}
