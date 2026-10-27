package com.confia.shared.jobs;

import static com.confia.shared.jobs.RecurringFixtures.NAME;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.TransactionRunner;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Recurring jobs run once for each configured institution and for no other
 * (background-jobs-with-db-scheduler design.md decision 10; scenarios BJ30, BJ65 and BJ66): the same
 * occurrence, with its own run id as correlation and request, the system actor and the institution of
 * each pass; a failure in one institution does not stop the next, is written as {@code
 * job_institution_failed}, and leaves the occurrence recorded as failed.
 */
class RecurringJobPerInstitutionIT extends WorkerJobIT {

    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID NOT_CONFIGURED = UUID.fromString("00000000-0000-0000-0000-0000000000d3");

    @BeforeEach
    void cleanProbe() {
        RecurringFixtures.reset();
    }

    @Test
    void eachCycleRunsOnceForEachConfiguredInstitutionAndForNoOther() {
        try (ConfigurableApplicationContext worker = WorkerHarness.startWith(RecurringFixtures.Registered.class,
                List.of(FIRST, SECOND))) {
            RecurringFixtures.settleStartupRun(2);
            RecurringFixtures.runNow();
            Eventually.within(Duration.ofSeconds(60), () ->
                    assertThat(RecurringFixtures.SEEN).hasSize(2));
        }

        assertThat(RecurringFixtures.institutionsSeen()).containsExactly(FIRST, SECOND)
                .doesNotContain(NOT_CONFIGURED);
        assertThat(RecurringFixtures.SEEN).extracting(RecurringFixtures.Seen::actorKind).containsOnly("system");
        assertThat(RecurringFixtures.SEEN).extracting(RecurringFixtures.Seen::mdcInstitution)
                .containsExactly(FIRST.toString(), SECOND.toString());
    }

    @Test
    void theRunIdIsTheCorrelationAndTheRequestOfTheOccurrenceAndOfItsAuditEntries() {
        TransactionRunner runner = transactionRunner();
        AuditLogWriter writer = new JooqAuditLogWriter(dsl);
        try (ConfigurableApplicationContext worker = WorkerHarness.startWith(RecurringFixtures.Registered.class,
                List.of(FIRST))) {
            RecurringFixtures.settleStartupRun(1);
            RecurringFixtures.runNow();
            Eventually.within(Duration.ofSeconds(60), () -> assertThat(RecurringFixtures.SEEN).hasSize(1));
        }

        RecurringFixtures.Seen seen = RecurringFixtures.SEEN.get(0);
        assertThat(seen.correlationId()).isEqualTo(seen.runId().toString()).isEqualTo(seen.requestId());
        RecurringRun run = new RecurringRun(NAME, seen.runId(), java.time.Instant.now());
        runner.execute(run.systemContextFor(new com.confia.kernel.InstitutionId(FIRST)), () -> {
            writer.append(new AuditEntry(FIRST, null, "system", "recurring probe", null, null, seen.runId(), null,
                    "shared.recurring_probe.ran", "shared.job_run", seen.runId().toString(), "success", null, null,
                    null, null));
            return null;
        });
        assertThat(SchedulingFixtures.auditRowsOf(FIRST, seen.runId())).isEqualTo(1);
    }

    @Test
    void aFailureInTheFirstInstitutionDoesNotStopTheSecondAndTheOccurrenceIsRecordedAsFailed() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        try (ConfigurableApplicationContext worker = WorkerHarness.startWith(RecurringFixtures.Registered.class,
                List.of(FIRST, SECOND))) {
            root.addAppender(appender);
            RecurringFixtures.settleStartupRun(2);
            RecurringFixtures.failFor = FIRST;
            RecurringFixtures.runNow();
            Eventually.within(Duration.ofSeconds(60), () -> {
                assertThat(RecurringFixtures.SEEN).hasSize(2);
                assertThat(RecurringFixtures.state().consecutiveFailures()).isEqualTo(1);
            });
        } finally {
            root.detachAppender(appender);
        }

        assertThat(RecurringFixtures.institutionsSeen()).as("the second institution ran after the failure")
                .containsExactly(FIRST, SECOND);
        assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("job_institution_failed")).toList())
                .containsExactly("job_institution_failed task=" + NAME + " institution=" + FIRST
                        + " exception=java.lang.IllegalStateException");
    }
}
