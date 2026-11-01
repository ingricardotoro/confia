package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.kernel.InstitutionId;
import com.confia.shared.jobs.JobCalendar;
import com.confia.shared.jobs.RecurringJob;
import com.confia.shared.jobs.RecurringRun;
import com.confia.shared.observability.events.OperationalEventLog;
import com.confia.shared.security.TransactionRunner;
import com.confia.support.JdbcStubTransactions;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The two cadences of the chain verifier without a database (background-jobs-with-db-scheduler design.md
 * decision 19; scenarios AT10 to AT13, AT16, AT30 and AT31): the daily one verifies from the start of
 * the window, the weekly one the whole chain; each leaves one entry with the scope and the rows verified
 * and the event that matches the result, the entry is written after the walk in a transaction of its
 * own, a divergence leaves a failed entry and an {@code ERROR} and then fails the occurrence, and a
 * technical failure leaves nothing.
 */
class AuditChainVerificationJobsTest {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-10-25T09:00:00Z");
    private static final Instant WINDOW_START = Instant.parse("2026-09-01T06:00:00Z");

    private final List<String> order = new ArrayList<>();
    private final List<AuditEntry> entries = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final TransactionRunner runner = JdbcStubTransactions.runner(order);

    @BeforeEach
    void captureLogs() {
        logs.start();
        ((Logger) LoggerFactory.getLogger(OperationalEventLog.class)).addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(OperationalEventLog.class)).detachAppender(logs);
    }

    /** A verifier that, like the real one, walks inside a transaction of its own, and answers {@code result}. */
    private AuditChainVerifier verifier(Supplier<AuditChainVerification> result) {
        return new AuditChainVerifier() {
            @Override
            public AuditChainVerification verifyChainOf(InstitutionId institutionId) {
                asked.add("full/" + institutionId.value());
                return walk(result);
            }

            @Override
            public AuditChainVerification verifySince(InstitutionId institutionId, Instant since) {
                asked.add("since " + since + "/" + institutionId.value());
                return walk(result);
            }

            private AuditChainVerification walk(Supplier<AuditChainVerification> answer) {
                return runner.execute(new com.confia.shared.security.SecurityContext("", "system",
                        INSTITUTION.value().toString(), UUID.randomUUID().toString()), () -> {
                    order.add("walk");
                    return answer.get();
                });
            }
        };
    }

    private AuditChainVerificationJobs jobs(Supplier<AuditChainVerification> result) {
        AuditLogWriter writer = entry -> {
            order.add("entry");
            entries.add(entry);
        };
        return new AuditChainVerificationJobs(runner, verifier(result), writer, new OperationalEventLog(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static RecurringRun run(String task) {
        return new RecurringRun(task, UUID.randomUUID(), NOW);
    }

    private static AuditChainVerification intact(long rows) {
        return new AuditChainVerification.Intact(INSTITUTION, rows, new byte[32]);
    }

    private static AuditChainVerification diverged() {
        return new AuditChainVerification.Diverged(INSTITUTION, 7, 42,
                AuditChainVerification.Divergence.ROW_HASH_MISMATCH, new byte[32], new byte[32]);
    }

    private List<String> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().map(pair -> pair.key + "=" + pair.value).toList();
    }

    @Test
    void theTwoJobsAreNamedAndScheduledAsTheChangeWritesThem() {
        AuditChainVerificationJobs jobs = jobs(() -> intact(1));

        assertThat(jobs.daily()).extracting(RecurringJob::name, RecurringJob::calendar).containsExactly(
                "shared.audit-chain-verification-daily", new JobCalendar.DailyAt(LocalTime.of(3, 0)));
        assertThat(jobs.weekly()).extracting(RecurringJob::name, RecurringJob::calendar).containsExactly(
                "shared.audit-chain-verification-weekly",
                new JobCalendar.WeeklyAt(DayOfWeek.SUNDAY, LocalTime.of(3, 30)));
    }

    /** Scenarios AT10 and AT13: the daily cadence asks for the window and leaves the verified entry and the INFO. */
    @Test
    void theDailyCadenceVerifiesTheWindowAndRecordsAnIntactChain() {
        AuditChainVerificationJobs jobs = jobs(() -> intact(15));
        RecurringRun run = run(AuditChainVerificationJobs.DAILY_TASK);

        jobs.daily().handler().runFor(INSTITUTION, run);

        assertThat(asked).containsExactly("since " + WINDOW_START + "/" + INSTITUTION.value());
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.institutionId()).isEqualTo(INSTITUTION.value());
            assertThat(entry.action()).isEqualTo("shared.audit.chain_verified");
            assertThat(entry.outcome()).isEqualTo("success");
            assertThat(entry.actorKind()).isEqualTo("system");
            assertThat(entry.actorId()).isNull();
            assertThat(entry.requestId()).isEqualTo(run.runId());
            assertThat(entry.entityType()).isEqualTo("shared.job_run");
            assertThat(entry.entityId()).isEqualTo(run.runId().toString());
            assertThat(entry.afterValue()).isEqualTo("{\"scope\":\"daily\",\"verifiedRows\":15}");
        });
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo("audit_chain_verified");
            assertThat(fields(event)).containsExactly("runId=" + run.runId(),
                    "task=shared.audit-chain-verification-daily", "institution=" + INSTITUTION.value(),
                    "scope=daily", "verifiedRows=15");
        });
    }

    /** Scenario AT11: the weekly cadence walks the whole chain from the genesis row. */
    @Test
    void theWeeklyCadenceVerifiesTheWholeChain() {
        AuditChainVerificationJobs jobs = jobs(() -> intact(40));

        jobs.weekly().handler().runFor(INSTITUTION, run(AuditChainVerificationJobs.WEEKLY_TASK));

        assertThat(asked).containsExactly("full/" + INSTITUTION.value());
        assertThat(entries).singleElement()
                .satisfies(entry -> assertThat(entry.afterValue())
                        .isEqualTo("{\"scope\":\"weekly\",\"verifiedRows\":40}"));
        assertThat(fields(logs.list.get(0))).contains("scope=weekly", "verifiedRows=40");
    }

    /** Scenario AT30: nothing to verify still leaves the entry with zero rows, and a WARN, never an ERROR. */
    @Test
    void anInstitutionWithoutRowsLeavesTheEntryWithZeroRowsAndAWarning() {
        AuditChainVerificationJobs jobs = jobs(() -> new AuditChainVerification.Empty(INSTITUTION));
        RecurringRun run = run(AuditChainVerificationJobs.DAILY_TASK);

        jobs.daily().handler().runFor(INSTITUTION, run);

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("shared.audit.chain_verified");
            assertThat(entry.outcome()).isEqualTo("success");
            assertThat(entry.afterValue()).isEqualTo("{\"scope\":\"daily\",\"verifiedRows\":0}");
        });
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo("audit_chain_empty_for_configured_institution");
            assertThat(fields(event)).containsExactly("runId=" + run.runId(),
                    "task=shared.audit-chain-verification-daily", "institution=" + INSTITUTION.value(),
                    "scope=daily");
        });
    }

    /** Scenario AT12: a divergence leaves the failed entry and the S1 ERROR, and then fails the occurrence. */
    @Test
    void aDivergenceLeavesAFailedEntryAndAnErrorAndFailsTheOccurrence() {
        AuditChainVerificationJobs jobs = jobs(AuditChainVerificationJobsTest::diverged);
        RecurringRun run = run(AuditChainVerificationJobs.WEEKLY_TASK);

        assertThatThrownBy(() -> jobs.weekly().handler().runFor(INSTITUTION, run))
                .isInstanceOfSatisfying(AuditChainBrokenException.class, thrown -> {
                    assertThat(thrown.institutionId()).isEqualTo(INSTITUTION);
                    assertThat(thrown.firstDivergentId()).isEqualTo(42L);
                    assertThat(thrown.getMessage()).doesNotContain("42");
                });

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.institutionId()).isEqualTo(INSTITUTION.value());
            assertThat(entry.action()).isEqualTo("shared.audit.chain_verification_failed");
            assertThat(entry.outcome()).isEqualTo("error");
            assertThat(entry.afterValue()).isEqualTo("{\"scope\":\"weekly\",\"verifiedRows\":7,"
                    + "\"firstDivergentId\":42,\"divergence\":\"ROW_HASH_MISMATCH\"}");
        });
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("audit_chain_broken");
            assertThat(fields(event)).containsExactly("runId=" + run.runId(),
                    "task=shared.audit-chain-verification-weekly", "institution=" + INSTITUTION.value(),
                    "scope=weekly", "firstDivergentId=42", "divergence=ROW_HASH_MISMATCH");
        });
    }

    /** R3: the tamper alert does not depend on the audit write; a failed write is reported apart. */
    @Test
    void aBrokenChainRaisesTheAlertAndTheBrokenChainErrorEvenWhenItsEntryCannotBeWritten() {
        AuditLogWriter failing = entry -> {
            throw new IllegalStateException("the entry is refused");
        };
        AuditChainVerificationJobs jobs = new AuditChainVerificationJobs(runner, verifier(
                AuditChainVerificationJobsTest::diverged), failing, new OperationalEventLog(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        RecurringRun run = run(AuditChainVerificationJobs.WEEKLY_TASK);

        assertThatThrownBy(() -> jobs.weekly().handler().runFor(INSTITUTION, run))
                .isInstanceOfSatisfying(AuditChainBrokenException.class,
                        thrown -> assertThat(thrown.firstDivergentId()).isEqualTo(42L));

        assertThat(logs.list).hasSize(2);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.ERROR);
        assertThat(logs.list.get(0).getFormattedMessage()).isEqualTo("audit_chain_broken");
        assertThat(logs.list.get(1).getLevel()).isEqualTo(Level.ERROR);
        assertThat(logs.list.get(1).getFormattedMessage()).isEqualTo("audit_chain_broken_entry_unwritten");
        assertThat(fields(logs.list.get(1))).containsExactly("runId=" + run.runId(),
                "task=shared.audit-chain-verification-weekly", "institution=" + INSTITUTION.value(),
                "scope=weekly", "errorType=IllegalStateException");
        assertThat(logs.list.get(1).getFormattedMessage() + fields(logs.list.get(1)))
                .doesNotContain("the entry is refused");
    }

    /** Scenario AT31: the walk commits first, and the entry is written afterwards in a transaction of its own. */
    @Test
    void theEntryIsWrittenAfterTheWalkInATransactionOfItsOwn() {
        AuditChainVerificationJobs jobs = jobs(AuditChainVerificationJobsTest::diverged);

        assertThatThrownBy(() -> jobs.daily().handler().runFor(INSTITUTION,
                run(AuditChainVerificationJobs.DAILY_TASK))).isInstanceOf(AuditChainBrokenException.class);

        assertThat(order).containsExactly("walk", "commit", "entry", "commit");
    }

    /** Scenario AT16: a technical failure leaves no entry and no event, and is not swallowed. */
    @Test
    void aTechnicalFailureLeavesNoEntryAndNoEventAndPropagates() {
        AuditChainVerificationJobs jobs = jobs(() -> {
            throw new IllegalStateException("the database is unreachable");
        });

        assertThatThrownBy(() -> jobs.daily().handler().runFor(INSTITUTION,
                run(AuditChainVerificationJobs.DAILY_TASK)))
                .isInstanceOf(IllegalStateException.class).hasMessage("the database is unreachable");

        assertThat(entries).isEmpty();
        assertThat(logs.list).isEmpty();
        assertThat(order).containsExactly("walk", "rollback");
    }

    /** An entry that cannot be written fails the occurrence before any event says the chain was verified. */
    @Test
    void anEntryThatFailsFailsTheOccurrenceAndWritesNoEvent() {
        AuditLogWriter failing = entry -> {
            throw new IllegalStateException("the entry is refused");
        };
        AuditChainVerificationJobs jobs = new AuditChainVerificationJobs(runner,
                verifier(() -> intact(3)), failing, new OperationalEventLog(), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> jobs.daily().handler().runFor(INSTITUTION,
                run(AuditChainVerificationJobs.DAILY_TASK))).isInstanceOf(IllegalStateException.class);

        assertThat(logs.list).isEmpty();
    }

    @Test
    void theConstructorRequiresEveryCollaborator() {
        AuditLogWriter writer = entry -> { };
        AuditChainVerifier verifier = verifier(() -> intact(1));
        OperationalEventLog log = new OperationalEventLog();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThatThrownBy(() -> new AuditChainVerificationJobs(null, verifier, writer, log, clock))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("runner");
        assertThatThrownBy(() -> new AuditChainVerificationJobs(runner, null, writer, log, clock))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("verifier");
        assertThatThrownBy(() -> new AuditChainVerificationJobs(runner, verifier, null, log, clock))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("auditLogWriter");
        assertThatThrownBy(() -> new AuditChainVerificationJobs(runner, verifier, writer, null, clock))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("eventLog");
        assertThatThrownBy(() -> new AuditChainVerificationJobs(runner, verifier, writer, log, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("clock");
    }
}
