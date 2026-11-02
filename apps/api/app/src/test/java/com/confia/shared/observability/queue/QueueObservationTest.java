package com.confia.shared.observability.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import com.confia.shared.jobs.JobCalendar;
import com.confia.shared.jobs.RecurringJob;
import com.confia.shared.jobs.RecurringRun;
import com.confia.shared.security.TransactionRunner;
import com.confia.support.JdbcStubTransactions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The recurring job of the worker that observes the queue (background-jobs-with-db-scheduler design.md decision
 * 20; scenario BJ68): every 60 seconds, one reading in a read-only transaction and one publication for each
 * occurrence, however many institutions the worker is configured for, because the queue is one for the whole
 * system.
 */
class QueueObservationTest {

    private static final Instant NOW = Instant.parse("2026-11-01T18:00:00Z");
    private static final InstitutionId FIRST = new InstitutionId(UUID.randomUUID());
    private static final InstitutionId SECOND = new InstitutionId(UUID.randomUUID());
    private static final QueueSnapshot SNAPSHOT = new QueueSnapshot(1, 2, 3, Map.of("identity.a", 1L),
            Duration.ofSeconds(120));

    private final List<String> transactions = new ArrayList<>();
    private final List<String> readOnlyFlags = new ArrayList<>();
    private final List<Instant> reads = new ArrayList<>();
    private final List<QueueSnapshot> published = new ArrayList<>();
    private final AtomicBoolean readerFails = new AtomicBoolean();
    private final TransactionRunner runner = JdbcStubTransactions.runner(transactions, readOnlyFlags);
    private final QueueObservation observation = new QueueObservation(runner, now -> {
        reads.add(now);
        if (readerFails.get()) {
            throw new IllegalStateException("the queue cannot be read");
        }
        return SNAPSHOT;
    }, published::add, Clock.fixed(NOW, ZoneOffset.UTC));

    private static RecurringRun run(UUID runId) {
        return new RecurringRun(QueueObservation.TASK_NAME, runId, NOW);
    }

    @Test
    void theJobRunsEverySixtySecondsUnderTheNameOfTheCatalog() {
        RecurringJob job = observation.job();

        assertThat(job.name()).isEqualTo("shared.queue-observation").isEqualTo(QueueObservation.TASK_NAME);
        assertThat(job.calendar()).isEqualTo(new JobCalendar.Every(Duration.ofSeconds(60)));
    }

    @Test
    void anOccurrenceReadsTheQueueAtTheInstantOfTheClockAndPublishesWhatItRead() {
        observation.job().handler().runFor(FIRST, run(UUID.randomUUID()));

        assertThat(reads).containsExactly(NOW);
        assertThat(published).containsExactly(SNAPSHOT);
    }

    @Test
    void theReadingIsDoneInsideAReadOnlyTransactionThatCommits() {
        observation.job().handler().runFor(FIRST, run(UUID.randomUUID()));

        assertThat(readOnlyFlags).contains("true");
        assertThat(transactions).containsExactly("commit");
    }

    @Test
    void twoInstitutionsOfTheSameOccurrenceLeaveOnePublication() {
        UUID occurrence = UUID.randomUUID();

        observation.job().handler().runFor(FIRST, run(occurrence));
        observation.job().handler().runFor(SECOND, run(occurrence));

        assertThat(reads).hasSize(1);
        assertThat(published).hasSize(1);
    }

    @Test
    void theNextOccurrenceReadsAndPublishesAgain() {
        observation.job().handler().runFor(FIRST, run(UUID.randomUUID()));
        observation.job().handler().runFor(FIRST, run(UUID.randomUUID()));

        assertThat(reads).hasSize(2);
        assertThat(published).hasSize(2);
    }

    @Test
    void aFailedReadingPublishesNothingAndLeavesTheNextInstitutionOfTheOccurrenceFreeToTry() {
        UUID occurrence = UUID.randomUUID();
        readerFails.set(true);

        assertThatThrownBy(() -> observation.job().handler().runFor(FIRST, run(occurrence)))
                .isInstanceOf(IllegalStateException.class);
        readerFails.set(false);
        observation.job().handler().runFor(SECOND, run(occurrence));

        assertThat(reads).hasSize(2);
        assertThat(published).containsExactly(SNAPSHOT);
    }

    @Test
    void theCollaboratorsAreRequired() {
        assertThatThrownBy(() -> new QueueObservation(null, now -> SNAPSHOT, published::add,
                Clock.fixed(NOW, ZoneOffset.UTC))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueueObservation(runner, null, published::add,
                Clock.fixed(NOW, ZoneOffset.UTC))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueueObservation(runner, now -> SNAPSHOT, null,
                Clock.fixed(NOW, ZoneOffset.UTC))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueueObservation(runner, now -> SNAPSHOT, published::add, null))
                .isInstanceOf(NullPointerException.class);
    }
}
