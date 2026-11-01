package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The start of the daily verification window (background-jobs-with-db-scheduler design.md decision 19,
 * owner decision D-N2 of 2026-10-24; scenarios AT23 and AT24): 00:00 of the first day of the previous
 * calendar month in {@code America/Tegucigalpa}, which is 06:00 UTC because Honduras has no daylight
 * saving time. It is the month of the calendar, not a rolling window of 30 days, and the month is the
 * one of Tegucigalpa, not the one of UTC. Every case fixes the clock.
 */
class AuditChainDailyWindowTest {

    private static Instant startAt(String now) {
        return AuditChainVerificationJobs.dailyWindowStart(Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }

    /** Scenario AT23: the run of 2026-10-25 at 03:00 starts on 2026-09-01 at 00:00 in Tegucigalpa. */
    @Test
    void aRunOnTheTwentyFifthOfOctoberStartsOnTheFirstOfSeptember() {
        assertThat(startAt("2026-10-25T09:00:00Z")).isEqualTo(Instant.parse("2026-09-01T06:00:00Z"));
    }

    /** Scenario AT23: 30 rolling days would start on the 25th of September; the window is longer. */
    @Test
    void itIsNotARollingWindowOfThirtyDays() {
        Instant thirtyDaysBefore = Instant.parse("2026-10-25T09:00:00Z").minus(Duration.ofDays(30));

        assertThat(startAt("2026-10-25T09:00:00Z")).isBefore(thirtyDaysBefore);
        assertThat(startAt("2026-10-25T09:00:00Z")).isEqualTo(Instant.parse("2026-09-01T06:00:00Z"));
    }

    /** Scenario AT24: the start only changes when the month does, whatever the day. */
    @ParameterizedTest(name = "{0} starts on {1}")
    @CsvSource({
            "2026-10-01T09:00:00Z, 2026-09-01T06:00:00Z",
            "2026-10-15T09:00:00Z, 2026-09-01T06:00:00Z",
            "2026-10-31T09:00:00Z, 2026-09-01T06:00:00Z",
            "2026-11-01T09:00:00Z, 2026-10-01T06:00:00Z",
            "2026-03-01T09:00:00Z, 2026-02-01T06:00:00Z",
            "2026-07-15T09:00:00Z, 2026-06-01T06:00:00Z",
            "2026-01-31T09:00:00Z, 2025-12-01T06:00:00Z",
            "2026-01-01T09:00:00Z, 2025-12-01T06:00:00Z",
            "2027-01-15T09:00:00Z, 2026-12-01T06:00:00Z"})
    void theStartIsTheFirstDayOfThePreviousMonthInTegucigalpa(String now, String expectedStart) {
        assertThat(startAt(now)).isEqualTo(Instant.parse(expectedStart));
    }

    /** The month is Tegucigalpa's: 03:00 UTC on 1 November is still 21:00 of 31 October there. */
    @Test
    void theMonthIsTheOneOfTegucigalpaAndNotTheOneOfUtc() {
        assertThat(startAt("2026-11-01T03:00:00Z")).isEqualTo(Instant.parse("2026-09-01T06:00:00Z"));
        assertThat(startAt("2026-11-01T05:59:59Z")).as("one second before midnight in Tegucigalpa")
                .isEqualTo(Instant.parse("2026-09-01T06:00:00Z"));
        assertThat(startAt("2026-11-01T06:00:00Z")).as("midnight in Tegucigalpa")
                .isEqualTo(Instant.parse("2026-10-01T06:00:00Z"));
    }

    /** The window is the same whatever zone the clock itself carries. */
    @Test
    void theZoneOfTheClockDoesNotChangeTheStart() {
        Instant now = Instant.parse("2026-11-01T03:00:00Z");

        assertThat(AuditChainVerificationJobs.dailyWindowStart(Clock.fixed(now, ZoneOffset.UTC)))
                .isEqualTo(AuditChainVerificationJobs.dailyWindowStart(
                        Clock.fixed(now, ZoneOffset.ofHours(14))))
                .isEqualTo(AuditChainVerificationJobs.dailyWindowStart(
                        Clock.fixed(now, ZoneOffset.ofHours(-11))));
    }
}
