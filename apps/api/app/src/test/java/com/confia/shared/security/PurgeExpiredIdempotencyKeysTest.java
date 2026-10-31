package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.identity.application.JdbcStubTransactions;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.jobs.RecurringRun;
import com.confia.shared.observability.events.OperationalEventLog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntToLongFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The purge of expired idempotency keys and the batch loop it shares with the identity purges, without a
 * database (scenarios I274 to I276 and AT18 to AT21 and AT27 of background-jobs-with-db-scheduler,
 * design.md decision 17): the cutoff is the caller's clock minus a day, the limit is 5 000, a batch that
 * fills asks for another, a short one ends the run, 200 full ones end it with a warning, and every batch
 * is its own transaction together with its entry, also when it found nothing.
 */
class PurgeExpiredIdempotencyKeysTest {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-10-25T10:40:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-10-24T10:40:00Z");
    private static final RecurringRun RUN = new RecurringRun(PurgeExpiredIdempotencyKeys.TASK_NAME,
            UUID.randomUUID(), NOW);

    private final List<String> transaction = new ArrayList<>();
    private final List<AuditEntry> entries = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        ((Logger) LoggerFactory.getLogger(OperationalEventLog.class)).addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(OperationalEventLog.class)).detachAppender(logs);
    }

    /** A store that answers the count the script gives for that call number (from 1) and remembers what it was asked. */
    private IdempotencyRecordStore store(IntToLongFunction script) {
        return new IdempotencyRecordStore() {
            @Override
            public long purgeExpired(Instant cutoff, int limit) {
                asked.add(cutoff + "/" + limit);
                return script.applyAsLong(asked.size());
            }

            @Override
            public Optional<IdempotencyRecord> lockExisting(InstitutionId institutionId, IdempotencyKey key) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void insertInProgress(InstitutionId institutionId, IdempotencyKey key, String requestHash,
                    Instant createdAt, Instant expiresAt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void restartExpired(InstitutionId institutionId, IdempotencyKey key, String requestHash,
                    Instant expiresAt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void complete(InstitutionId institutionId, IdempotencyKey key, IdempotentResponse response,
                    Instant completedAt) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private PurgeExpiredIdempotencyKeys purge(IntToLongFunction script) {
        return new PurgeExpiredIdempotencyKeys(JdbcStubTransactions.runner(transaction), store(script),
                entries::add, new OperationalEventLog(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void theCutoffIsTheClockMinusADayAndTheLimitIsFiveThousand() {
        purge(call -> 0).runFor(INSTITUTION, RUN);

        assertThat(asked).containsExactly(CUTOFF + "/5000");
    }

    /** Scenarios AT18, AT19 and AT20: one entry, with the count even when it is zero, and nothing of the rows. */
    @Test
    void aBatchThatFoundNothingStillLeavesItsEntryWithCountZeroAndNothingElse() {
        purge(call -> 0).runFor(INSTITUTION, RUN);

        assertThat(entries).singleElement().isEqualTo(new AuditEntry(INSTITUTION.value(), null, "system",
                "retention-purge", null, null, RUN.runId(), null, "shared.idempotency_key.purged",
                "shared.job_run", RUN.runId().toString(), "success", null,
                "{\"purged\":0,\"cutoff\":\"" + CUTOFF + "\",\"batch\":1}", null, null));
    }

    /** Scenarios I274 and AT27: 5 000, 5 000 and 2 000, one transaction and one entry each, no warning. */
    @Test
    void batchesRepeatUntilOneComesBackShortAndEachIsItsOwnTransactionWithItsEntry() {
        purge(call -> call < 3 ? 5_000 : 2_000).runFor(INSTITUTION, RUN);

        assertThat(asked).hasSize(3);
        assertThat(transaction).containsExactly("commit", "commit", "commit");
        assertThat(entries).extracting(AuditEntry::afterValue).containsExactly(
                "{\"purged\":5000,\"cutoff\":\"" + CUTOFF + "\",\"batch\":1}",
                "{\"purged\":5000,\"cutoff\":\"" + CUTOFF + "\",\"batch\":2}",
                "{\"purged\":2000,\"cutoff\":\"" + CUTOFF + "\",\"batch\":3}");
        assertThat(logs.list).isEmpty();
    }

    @Test
    void aBatchOfExactlyFiveThousandAsksForAnotherAndOneOfFourThousandNineHundredNinetyNineDoesNot() {
        purge(call -> call == 1 ? 5_000 : 0).runFor(INSTITUTION, RUN);
        assertThat(asked).as("5 000 is a full batch").hasSize(2);

        asked.clear();
        purge(call -> 4_999).runFor(INSTITUTION, RUN);
        assertThat(asked).as("4 999 is a short one").hasSize(1);
    }

    /** Scenario I275: 200 full batches leave 200 entries and the warning, and not one more call. */
    @Test
    void twoHundredFullBatchesLeaveTwoHundredEntriesAndTheBacklogWarning() {
        purge(call -> 5_000).runFor(INSTITUTION, RUN);

        assertThat(asked).as("exactly 200 batches").hasSize(200);
        assertThat(entries).hasSize(200);
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo("retention_purge_backlog");
            assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key + "=" + pair.value)
                    .containsExactly("runId=" + RUN.runId(), "task=shared.purge-idempotency-keys",
                            "institution=" + INSTITUTION.value());
        });
    }

    @Test
    void aRunWhoseTwoHundredthBatchComesBackShortHasNoWarning() {
        purge(call -> call < 200 ? 5_000 : 10).runFor(INSTITUTION, RUN);

        assertThat(asked).hasSize(200);
        assertThat(logs.list).isEmpty();
    }

    /** Scenario AT21: an entry that fails after the deletion takes the batch with it. */
    @Test
    void anEntryThatFailsRollsTheBatchBackAndEndsTheRun() {
        PurgeExpiredIdempotencyKeys purge = new PurgeExpiredIdempotencyKeys(
                JdbcStubTransactions.runner(transaction), store(call -> 5_000),
                entry -> {
                    throw new IllegalStateException("the entry is lost");
                }, new OperationalEventLog(), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> purge.runFor(INSTITUTION, RUN)).isInstanceOf(IllegalStateException.class);

        assertThat(transaction).containsExactly("rollback");
        assertThat(asked).as("the next batch was not tried").hasSize(1);
    }
}
