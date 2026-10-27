package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A failed recurring occurrence is not retried and stays visible until the next success
 * (background-jobs-with-db-scheduler design.md decision 9; scenarios BJ24 and BJ61): it is rescheduled
 * to its next occurrence an hour away, not to a retry, with {@code consecutive_failures} at 1; the
 * next successful occurrence resets the count to 0.
 */
class RecurringFailureStateIT extends WorkerJobIT {

    private static final UUID INSTITUTION = SchedulingFixtures.INSTITUTION;

    @BeforeEach
    void cleanProbe() {
        RecurringFixtures.reset();
    }

    @Test
    void aFailedOccurrenceIsNotRetriedAndASuccessResetsTheCount() {
        try (ConfigurableApplicationContext worker =
                WorkerHarness.startWith(RecurringFixtures.Registered.class, List.of(INSTITUTION))) {
            RecurringFixtures.settleStartupRun(1);
            RecurringFixtures.failFor = INSTITUTION;
            RecurringFixtures.runNow();
            Eventually.within(Duration.ofSeconds(60), () ->
                    assertThat(RecurringFixtures.state().consecutiveFailures()).isEqualTo(1));

            RecurringFixtures.State failed = RecurringFixtures.state();
            assertThat(failed.picked()).isFalse();
            assertThat(failed.executionTime()).as("next occurrence, an hour away, not a retry")
                    .isAfter(Instant.now().plus(Duration.ofMinutes(50)));
            Eventually.throughout(Duration.ofSeconds(4), () -> assertThat(RecurringFixtures.SEEN).hasSize(1));

            RecurringFixtures.failFor = null;
            RecurringFixtures.runNow();
            Eventually.within(Duration.ofSeconds(60), () -> {
                assertThat(RecurringFixtures.SEEN).hasSize(2);
                assertThat(RecurringFixtures.state().consecutiveFailures()).isZero();
            });
        }
    }
}
