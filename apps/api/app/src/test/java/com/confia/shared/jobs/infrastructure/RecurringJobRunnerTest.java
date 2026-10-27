package com.confia.shared.jobs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import com.confia.shared.jobs.JobCalendar;
import com.confia.shared.jobs.RecurringJob;
import com.confia.shared.jobs.RecurringRun;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * One occurrence of a recurring job (background-jobs-with-db-scheduler design.md decision 10; scenarios
 * BJ30, BJ65 and BJ66): once per configured institution in order, with the run id as correlation and
 * request, and a failure that is counted instead of stopping the others.
 */
class RecurringJobRunnerTest {

    private static final InstitutionId FIRST = new InstitutionId(new UUID(0, 1));
    private static final InstitutionId SECOND = new InstitutionId(new UUID(0, 2));
    private static final UUID RUN = new UUID(7, 7);
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    private final RecurringJobRunner runner = new RecurringJobRunner(
            new ConfiguredInstitutions(List.of(FIRST, SECOND)), Clock.fixed(NOW, ZoneOffset.UTC), () -> RUN);

    private static RecurringJob job(java.util.function.BiConsumer<InstitutionId, RecurringRun> body) {
        return new RecurringJob("shared.runner-probe", new JobCalendar.Every(Duration.ofSeconds(60)), body::accept);
    }

    @Test
    void runsOncePerInstitutionInOrderWithTheRunIdAndTheMdc() {
        List<InstitutionId> order = new ArrayList<>();
        List<RecurringRun> runs = new ArrayList<>();
        List<Map<String, String>> mdc = new ArrayList<>();

        runner.run(job((institution, run) -> {
            order.add(institution);
            runs.add(run);
            mdc.add(MDC.getCopyOfContextMap());
        }));

        assertThat(order).containsExactly(FIRST, SECOND);
        assertThat(runs).allSatisfy(run -> {
            assertThat(run.runId()).isEqualTo(RUN);
            assertThat(run.taskName()).isEqualTo("shared.runner-probe");
            assertThat(run.startedAt()).isEqualTo(NOW);
        });
        assertThat(mdc).extracting(m -> m.get("institutionId"))
                .containsExactly(FIRST.value().toString(), SECOND.value().toString());
        assertThat(mdc).allSatisfy(m -> assertThat(m).containsEntry("correlationId", RUN.toString())
                .containsEntry("requestId", RUN.toString()).containsEntry("actorKind", "system")
                .containsEntry("jobTask", "shared.runner-probe"));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void aFailureDoesNotStopTheNextInstitutionAndIsReportedAtTheEnd() {
        List<InstitutionId> order = new ArrayList<>();

        assertThatThrownBy(() -> runner.run(job((institution, run) -> {
            order.add(institution);
            if (institution.equals(FIRST)) {
                throw new IllegalStateException("secret-value-1");
            }
        }))).isInstanceOfSatisfying(RecurringJobFailedException.class, e -> {
            assertThat(e.taskName()).isEqualTo("shared.runner-probe");
            assertThat(e.failedInstitutions()).containsExactly(FIRST.value());
            assertThat(e.getCause()).isNull();
            assertThat(e.getMessage()).doesNotContain("secret-value-1").contains(FIRST.value().toString());
        });

        assertThat(order).as("the second institution ran after the failure").containsExactly(FIRST, SECOND);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void everyInstitutionFailingReportsEveryOne() {
        assertThatThrownBy(() -> runner.run(job((institution, run) -> {
            throw new IllegalStateException("x");
        }))).isInstanceOfSatisfying(RecurringJobFailedException.class,
                e -> assertThat(e.failedInstitutions()).containsExactly(FIRST.value(), SECOND.value()));
    }

    @Test
    void theDefaultRunIdIsFreshForEachOccurrence() {
        RecurringJobRunner defaults = new RecurringJobRunner(new ConfiguredInstitutions(List.of(FIRST)),
                Clock.systemUTC());
        List<UUID> ids = new ArrayList<>();

        defaults.run(job((institution, run) -> ids.add(run.runId())));
        defaults.run(job((institution, run) -> ids.add(run.runId())));

        assertThat(ids).hasSize(2).doesNotHaveDuplicates();
    }
}
