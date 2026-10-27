package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.jobs.infrastructure.JobCalendars;
import com.confia.shared.jobs.infrastructure.ParkedExecutions;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/**
 * Probe P-5 and scenarios BJ27 and BJ62 (background-jobs-with-db-scheduler design.md decision 7): every
 * calendar is translated to a schedule in {@code America/Tegucigalpa}, whatever the default zone of
 * the JVM, and none reaches the parking instant. The reference instant is Sunday 2026-10-25 at
 * 00:00 in Tegucigalpa, which is 06:00 UTC (Honduras has no daylight saving time).
 */
class RecurringJobCalendarTest {

    private static final Instant SUNDAY_MIDNIGHT = Instant.parse("2026-10-25T06:00:00Z");

    private static Instant next(JobCalendar calendar, Instant from) {
        Schedule schedule = JobCalendars.scheduleOf(calendar);
        return schedule.getNextExecutionTime(ExecutionComplete.simulatedSuccess(from));
    }

    @Test
    void theZoneIsTegucigalpa() {
        assertThat(JobCalendars.ZONE).isEqualTo(ZoneId.of("America/Tegucigalpa"));
    }

    @Test
    void aDailyCalendarRunsAtThatTimeOfDayInTegucigalpa() {
        assertThat(next(new JobCalendar.DailyAt(LocalTime.of(3, 0)), SUNDAY_MIDNIGHT))
                .isEqualTo(Instant.parse("2026-10-25T09:00:00Z"));
        assertThat(next(new JobCalendar.DailyAt(LocalTime.of(4, 35)), SUNDAY_MIDNIGHT))
                .isEqualTo(Instant.parse("2026-10-25T10:35:00Z"));
        assertThat(next(new JobCalendar.DailyAt(LocalTime.of(3, 0)), Instant.parse("2026-10-25T09:00:00Z")))
                .as("after today's occurrence the next one is tomorrow's")
                .isEqualTo(Instant.parse("2026-10-26T09:00:00Z"));
    }

    @Test
    void aWeeklyCalendarRunsOnThatDayAtThatTime() {
        JobCalendar sunday = new JobCalendar.WeeklyAt(DayOfWeek.SUNDAY, LocalTime.of(3, 30));

        assertThat(next(sunday, SUNDAY_MIDNIGHT)).isEqualTo(Instant.parse("2026-10-25T09:30:00Z"));
        assertThat(next(sunday, Instant.parse("2026-10-25T09:30:00Z")))
                .as("the following Sunday").isEqualTo(Instant.parse("2026-11-01T09:30:00Z"));
        assertThat(next(new JobCalendar.WeeklyAt(DayOfWeek.WEDNESDAY, LocalTime.of(1, 5)), SUNDAY_MIDNIGHT))
                .isEqualTo(Instant.parse("2026-10-28T07:05:00Z"));
    }

    @Test
    void anIntervalCalendarWaitsThatLongAfterTheOccurrence() {
        assertThat(next(new JobCalendar.Every(Duration.ofSeconds(60)), SUNDAY_MIDNIGHT))
                .isEqualTo(SUNDAY_MIDNIGHT.plusSeconds(60));
    }

    @Test
    void theNextRunDoesNotDependOnTheDefaultZoneOfTheJvm() {
        TimeZone original = TimeZone.getDefault();
        try {
            JobCalendar twoAm = new JobCalendar.DailyAt(LocalTime.of(2, 0));
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Instant underUtc = next(twoAm, SUNDAY_MIDNIGHT);
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            Instant underNewYork = next(twoAm, SUNDAY_MIDNIGHT);

            assertThat(underUtc).isEqualTo(underNewYork).isEqualTo(Instant.parse("2026-10-25T08:00:00Z"));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void noCalendarComesNearTheParkingInstant() {
        for (JobCalendar calendar : new JobCalendar[] {new JobCalendar.DailyAt(LocalTime.of(3, 0)),
                new JobCalendar.WeeklyAt(DayOfWeek.SUNDAY, LocalTime.of(3, 30)),
                new JobCalendar.Every(Duration.ofSeconds(60))}) {
            Instant next = next(calendar, SUNDAY_MIDNIGHT);

            assertThat(next).isBefore(SUNDAY_MIDNIGHT.plus(Duration.ofDays(366)));
            assertThat(ParkedExecutions.isParked(next)).isFalse();
        }
    }
}
