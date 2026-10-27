package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Two workers on the same database run each occurrence once, and restarting a worker neither
 * duplicates the row nor repeats the occurrence (background-jobs-with-db-scheduler design.md decision
 * 11; scenarios BJ28 and BJ29). The table coordinates the replicas through the version of the row.
 */
class RecurringJobSingleRunIT extends WorkerJobIT {

    private static final UUID INSTITUTION = SchedulingFixtures.INSTITUTION;

    @BeforeEach
    void cleanProbe() {
        RecurringFixtures.reset();
    }

    @Test
    void twoWorkersRunAnOccurrenceExactlyOnce() {
        RecurringFixtures.holdMillis = 2_000;
        try (ConfigurableApplicationContext first = WorkerHarness.startWith(RecurringFixtures.Registered.class,
                List.of(INSTITUTION));
                ConfigurableApplicationContext second = WorkerHarness.startWith(
                        RecurringFixtures.Registered.class, List.of(INSTITUTION))) {
            RecurringFixtures.settleStartupRun(1);
            RecurringFixtures.runNow();
            Eventually.within(Duration.ofSeconds(60), () -> assertThat(RecurringFixtures.SEEN).hasSize(1));
            // Both workers keep polling every second while the occurrence is held and afterwards.
            Eventually.throughout(Duration.ofSeconds(6), () -> {
                assertThat(RecurringFixtures.SEEN).hasSize(1);
                assertThat(RecurringFixtures.MAX_CONCURRENT.get()).isEqualTo(1);
            });
        }
    }

    @Test
    void restartingTheWorkerKeepsOneRowAndDoesNotRepeatTheOccurrence() {
        try (ConfigurableApplicationContext worker =
                WorkerHarness.startWith(RecurringFixtures.Registered.class, List.of(INSTITUTION))) {
            RecurringFixtures.settleStartupRun(1);
            RecurringFixtures.runNow();
            Eventually.within(Duration.ofSeconds(60), () -> assertThat(RecurringFixtures.SEEN).hasSize(1));
        }

        try (ConfigurableApplicationContext restarted =
                WorkerHarness.startWith(RecurringFixtures.Registered.class, List.of(INSTITUTION))) {
            Eventually.throughout(Duration.ofSeconds(5), () -> {
                assertThat(RecurringFixtures.state().rows()).as("one row of the recurring task").isEqualTo(1);
                assertThat(RecurringFixtures.SEEN).as("today's occurrence is not repeated").hasSize(1);
            });
        }
    }
}
