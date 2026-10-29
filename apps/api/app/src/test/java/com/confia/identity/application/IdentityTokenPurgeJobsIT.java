package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.identity.infrastructure.JooqIdentityRetentionPurges;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.jobs.RecurringRun;
import com.confia.shared.observability.events.OperationalEventLog;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The daily purge of expired password-reset tokens against a real PostgreSQL (scenarios I256, I263,
 * I274, I275 and I276 of identity and AT18 to AT21 and AT27 to AT29 of audit-trail, of
 * background-jobs-with-db-scheduler design.md decision 17): one audit entry for every batch, also for an
 * empty one, batches until one comes back short, at most 200 of them with a warning when they run out,
 * the cutoff computed by the caller from its clock, and a batch that is its own transaction together
 * with its entry. The boundaries follow the database-clock rule: the clock of the use case is fixed at
 * {@code now()} of PostgreSQL, read once.
 */
class IdentityTokenPurgeJobsIT extends IdentityPurgeFixtures {

    private static final String TABLE = "identity_password_reset_token";
    private static final String TASK = PurgeExpiredPasswordResetTokens.TASK_NAME;
    private static final String ACTION = PurgeExpiredPasswordResetTokens.ACTION;
    private static final Duration WINDOW = Duration.ofHours(24);

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

    /** Scenarios AT18 and AT20: one entry for the batch, with the counts and the instant and nothing of the rows. */
    @Test
    void aBatchLeavesOneSystemEntryWithTheCountTheCutoffAndTheBatchNumberAndNothingElse() {
        Instant now = databaseNow();
        Instant cutoff = now.minus(WINDOW);
        UUID account = seedPlainAccount(institutionId);
        seedResetTokens(institutionId, account, 3, cutoff.minusSeconds(1));
        seedResetTokens(institutionId, account, 2, now.plus(Duration.ofMinutes(30)));
        RecurringRun run = runOf(TASK);

        resetPurge(fixedAt(now)).runFor(institutionId, run);

        assertThat(count(institutionId, TABLE)).as("the live tokens stay").isEqualTo(2);
        assertThat(entries(institutionId, ACTION)).singleElement().satisfies(entry -> {
            assertThat(entry.actorKind()).isEqualTo("system");
            assertThat(entry.actorId()).isNull();
            assertThat(entry.actorLabel()).isEqualTo("retention-purge");
            assertThat(entry.entityType()).isEqualTo("shared.job_run");
            assertThat(entry.entityId()).isEqualTo(run.runId().toString());
            assertThat(entry.requestId()).isEqualTo(run.runId());
            assertThat(entry.outcome()).isEqualTo("success");
            assertThat(detail(entry)).isEqualTo(detail("{\"purged\":3,\"cutoff\":\"" + cutoff + "\",\"batch\":1}"));
        });
    }

    /** Scenario AT19: a purge that found nothing leaves its entry too. */
    @Test
    void aPurgeThatFoundNothingStillLeavesItsEntryWithCountZero() {
        Instant now = databaseNow();
        RecurringRun run = runOf(TASK);

        resetPurge(fixedAt(now)).runFor(institutionId, run);

        assertThat(entries(institutionId, ACTION)).singleElement().satisfies(entry -> {
            assertThat(detail(entry)).isEqualTo(detail("{\"purged\":0,\"cutoff\":\"" + now.minus(WINDOW) + "\",\"batch\":1}"));
            assertThat(entry.requestId()).isEqualTo(run.runId());
        });
    }

    /** The database-clock rule: the token expired exactly at the cutoff stays, the one a second before goes. */
    @Test
    void theTokenAtTheExactCutoffStaysAndTheOneASecondBeforeItIsPurgedWithoutCrossingInstitutions() {
        Instant now = databaseNow();
        Instant cutoff = now.minus(WINDOW);
        UUID account = seedPlainAccount(institutionId);
        UUID foreign = seedPlainAccount(otherInstitution);
        seedResetTokens(institutionId, account, 1, cutoff.minusSeconds(1));
        seedResetTokens(institutionId, account, 1, cutoff);
        seedResetTokens(institutionId, account, 1, cutoff.plusSeconds(1));
        seedResetTokens(otherInstitution, foreign, 2, cutoff.minus(Duration.ofDays(3)));

        resetPurge(fixedAt(now)).runFor(institutionId, runOf(TASK));

        assertThat(count(institutionId, TABLE)).as("the border and the second after it").isEqualTo(2);
        assertThat(count(otherInstitution, TABLE)).as("another institution is untouched").isEqualTo(2);
        assertThat(entries(otherInstitution, ACTION)).isEmpty();
    }

    /** Scenarios I274 and AT27: three batches of 5 000, 5 000 and 2 000, one entry each, no warning. */
    @Test
    void batchesRepeatUntilOneComesBackShortAndEachLeavesItsOwnEntryWithoutAWarning() {
        Instant now = databaseNow();
        UUID account = seedPlainAccount(institutionId);
        seedResetTokens(institutionId, account, 12_000, now.minus(WINDOW).minusSeconds(1));
        RecurringRun run = runOf(TASK);

        resetPurge(fixedAt(now)).runFor(institutionId, run);

        assertThat(count(institutionId, TABLE)).as("nothing purgeable is left").isZero();
        List<AuditRowSnapshot> entries = entries(institutionId, ACTION);
        String cutoff = now.minus(WINDOW).toString();
        assertThat(entries).extracting(IdentityPurgeFixtures::detail).containsExactly(
                detail("{\"purged\":5000,\"cutoff\":\"" + cutoff + "\",\"batch\":1}"),
                detail("{\"purged\":5000,\"cutoff\":\"" + cutoff + "\",\"batch\":2}"),
                detail("{\"purged\":2000,\"cutoff\":\"" + cutoff + "\",\"batch\":3}"));
        assertThat(entries).extracting(AuditRowSnapshot::requestId).containsOnly(run.runId());
        assertThat(logs.list).as("no backlog warning").isEmpty();
    }

    /** Scenarios I275 and AT29: 200 full batches leave 200 entries and the warning; the next occurrence goes on. */
    @Test
    void twoHundredFullBatchesLeaveTwoHundredEntriesAndTheBacklogWarningAndTheNextRunContinues() {
        RecordingPurges purges = new RecordingPurges(call -> call <= 200 ? 5_000 : 100);
        RecurringRun first = runOf(TASK);

        resetPurge(purges, new JooqAuditLogWriter(dsl), fixedAt(databaseNow()))
                .runFor(institutionId, first);

        assertThat(purges.calls).as("exactly 200 batches").hasSize(200);
        assertThat(entries(institutionId, ACTION)).hasSize(200)
                .extracting(AuditRowSnapshot::requestId).containsOnly(first.runId());
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo("retention_purge_backlog");
            assertThat(event.getArgumentArray()).isNull();
            assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key + "=" + pair.value)
                    .containsExactly("runId=" + first.runId(), "task=" + TASK,
                            "institution=" + institutionId.value());
        });

        resetPurge(purges, new JooqAuditLogWriter(dsl), fixedAt(databaseNow()))
                .runFor(institutionId, runOf(TASK));

        assertThat(purges.calls).as("the next occurrence takes what was left").hasSize(201);
        assertThat(entries(institutionId, ACTION)).hasSize(201);
        assertThat(logs.list).as("and finishes without a new warning").hasSize(1);
    }

    /** Scenario I276: the cutoff is the caller's clock minus the window, and the limit is 5 000. */
    @Test
    void theBatchCallsTheFunctionWithTheCutoffComputedFromTheClockAndTheLimit() {
        RecordingPurges purges = new RecordingPurges(call -> 0);

        resetPurge(purges, new JooqAuditLogWriter(dsl), fixedAt(Instant.parse("2026-10-25T10:30:01Z")))
                .runFor(institutionId, runOf(TASK));

        assertThat(purges.calls).singleElement().satisfies(call -> {
            assertThat(call.operation()).isEqualTo("passwordResetTokens");
            assertThat(call.cutoff()).isEqualTo(Instant.parse("2026-10-24T10:30:01Z"));
            assertThat(call.limit()).isEqualTo(5_000);
        });
    }

    /** Scenarios I256 and I263: the second run purges nothing, changes nothing and does not fail. */
    @Test
    void aSecondRunChangesNothingReportsZeroAndDoesNotFail() {
        Instant now = databaseNow();
        UUID account = seedPlainAccount(institutionId);
        seedResetTokens(institutionId, account, 3, now.minus(WINDOW).minusSeconds(1));
        seedResetTokens(institutionId, account, 2, now.plus(Duration.ofMinutes(30)));
        PurgeExpiredPasswordResetTokens purge = resetPurge(fixedAt(now));

        purge.runFor(institutionId, runOf(TASK));
        long afterFirst = count(institutionId, TABLE);
        purge.runFor(institutionId, runOf(TASK));

        assertThat(count(institutionId, TABLE)).isEqualTo(afterFirst).isEqualTo(2);
        assertThat(entries(institutionId, ACTION)).extracting(IdentityPurgeFixtures::detail)
                .anySatisfy(value -> assertThat(value.get("purged").asLong()).isZero());
    }

    /** Scenario AT21: an entry that fails after the purge deleted its rows takes the purge with it. */
    @Test
    void anEntryThatFailsAfterTheDeletionRollsTheBatchBack() {
        Instant now = databaseNow();
        UUID account = seedPlainAccount(institutionId);
        seedResetTokens(institutionId, account, 3, now.minus(WINDOW).minusSeconds(1));
        AuditLogWriter writesThenFails = entry -> {
            new JooqAuditLogWriter(dsl).append(entry);
            throw new IllegalStateException("the transaction is lost after the entry");
        };

        assertThatThrownBy(() -> resetPurge(new JooqIdentityRetentionPurges(dsl),
                writesThenFails, fixedAt(now)).runFor(institutionId, runOf(TASK)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count(institutionId, TABLE)).as("the rows are still there").isEqualTo(3);
        assertThat(entries(institutionId, ACTION)).as("and so is no entry").isEmpty();
    }

    /** Every batch is its own transaction: a failure in the second leaves the first committed with its entry. */
    @Test
    void aFailureInTheSecondBatchLeavesTheFirstCommittedWithItsEntry() {
        Instant now = databaseNow();
        UUID account = seedPlainAccount(institutionId);
        seedResetTokens(institutionId, account, 6_000, now.minus(WINDOW).minusSeconds(1));
        AtomicInteger appended = new AtomicInteger();
        AuditLogWriter failsOnTheSecond = entry -> {
            new JooqAuditLogWriter(dsl).append(entry);
            if (appended.incrementAndGet() == 2) {
                throw new IllegalStateException("the second batch is lost");
            }
        };

        assertThatThrownBy(() -> resetPurge(new JooqIdentityRetentionPurges(dsl),
                failsOnTheSecond, fixedAt(now)).runFor(institutionId, runOf(TASK)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count(institutionId, TABLE)).as("only the first batch was committed").isEqualTo(1_000);
        assertThat(entries(institutionId, ACTION)).singleElement().satisfies(entry -> {
            assertThat(detail(entry).get("purged").asLong()).isEqualTo(5000L);
            assertThat(detail(entry).get("batch").asLong()).isEqualTo(1L);
        });
    }
}
