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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Recurring jobs run once for each configured institution and for no other
 * (background-jobs-with-db-scheduler design.md decision 10; scenarios BJ30, BJ65 and BJ66): the same
 * occurrence, with its own run id as correlation and request, the system actor and the institution of
 * each pass; a failure in one institution does not stop the next, is written as {@code
 * job_institution_failed}, and leaves the occurrence recorded as failed. The last two tests show it with
 * the real purges of idempotency keys and of login and TOTP backoff (task 3.7), whose failure in one
 * institution is a real one: a trigger of the database refuses their deletion there.
 */
class RecurringJobPerInstitutionIT extends WorkerJobIT {

    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID NOT_CONFIGURED = UUID.fromString("00000000-0000-0000-0000-0000000000d3");
    private static final UUID REFUSED = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final UUID ACCEPTED = UUID.fromString("00000000-0000-0000-0000-0000000000d5");
    private static final String IDEMPOTENCY_TASK = "shared.purge-idempotency-keys";
    private static final String LOGIN_TASK = "identity.purge-login-backoff";
    private static final String TOTP_TASK = "identity.purge-mfa-totp-backoff";

    /** The worker adds nothing of its own to the production registration. */
    @Configuration(proxyBeanMethods = false)
    static class NoFixtures {
    }

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

    /** Scenarios BJ30 and I262: the purges of idempotency and backoff run for both configured institutions, not a third. */
    @Test
    void thePurgesOfIdempotencyAndBackoffRunForEachConfiguredInstitutionAndForNoOther() {
        Instant idle = Instant.now().minus(Duration.ofDays(3));
        for (UUID institution : List.of(FIRST, SECOND, NOT_CONFIGURED)) {
            PurgeProbeRows.idempotencyKeys(institution, 3, idle);
            PurgeProbeRows.loginBackoff(institution, 3, idle);
            PurgeProbeRows.mfaTotpBackoff(institution, 1, idle);
        }
        try (ConfigurableApplicationContext worker = WorkerHarness.startWith(NoFixtures.class,
                List.of(FIRST, SECOND))) {
            Eventually.within(Duration.ofSeconds(60), () ->
                    assertThat(scheduled(IDEMPOTENCY_TASK, LOGIN_TASK, TOTP_TASK)).isEqualTo(3));
            dueNow(IDEMPOTENCY_TASK, LOGIN_TASK, TOTP_TASK);
            Eventually.within(Duration.ofSeconds(90), () -> assertThat(remaining(FIRST) + remaining(SECOND))
                    .isZero());
        }

        assertThat(remaining(NOT_CONFIGURED)).as("a third institution is not configured, so untouched").isEqualTo(7);
        assertThat(PurgeProbeRows.auditDetails(FIRST, "shared.idempotency_key.purged")).hasSize(1);
        assertThat(PurgeProbeRows.auditDetails(SECOND, "identity.login_backoff.purged")).hasSize(1);
        assertThat(PurgeProbeRows.auditDetails(NOT_CONFIGURED, "shared.idempotency_key.purged")).isEmpty();
    }

    /** Scenario BJ65, with the real purge: the institution whose deletion fails does not stop the other. */
    @Test
    void aPurgeThatFailsInOneInstitutionDoesNotStopTheOtherAndTheOccurrenceIsRecordedAsFailed() {
        Instant idle = Instant.now().minus(Duration.ofDays(3));
        PurgeProbeRows.idempotencyKeys(REFUSED, 3, idle);
        PurgeProbeRows.idempotencyKeys(ACCEPTED, 3, idle);
        PurgeProbeRows.run("""
                create function purge_probe_refuse() returns trigger language plpgsql as $$
                begin
                    if current_setting('app.institution_id', true) = '%s' then
                        raise exception 'purge_probe_refused';
                    end if;
                    return old;
                end $$
                """.formatted(REFUSED));
        PurgeProbeRows.run("create trigger purge_probe_refuse before delete on shared_idempotency_key "
                + "for each row execute function purge_probe_refuse()");
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        try (ConfigurableApplicationContext worker = WorkerHarness.startWith(NoFixtures.class,
                List.of(REFUSED, ACCEPTED))) {
            root.addAppender(appender);
            Eventually.within(Duration.ofSeconds(60), () -> assertThat(scheduled(IDEMPOTENCY_TASK)).isEqualTo(1));
            dueNow(IDEMPOTENCY_TASK);
            Eventually.within(Duration.ofSeconds(90), () -> {
                assertThat(PurgeProbeRows.count("shared_idempotency_key", ACCEPTED)).isZero();
                assertThat(PurgeProbeRows.scalar("select consecutive_failures from scheduled_tasks "
                        + "where task_name = ?", IDEMPOTENCY_TASK)).isEqualTo(1);
            });
        } finally {
            root.detachAppender(appender);
            PurgeProbeRows.run("drop trigger purge_probe_refuse on shared_idempotency_key");
            PurgeProbeRows.run("drop function purge_probe_refuse()");
        }

        assertThat(PurgeProbeRows.count("shared_idempotency_key", REFUSED)).as("the refused one kept its keys")
                .isEqualTo(3);
        assertThat(PurgeProbeRows.auditDetails(ACCEPTED, "shared.idempotency_key.purged")).hasSize(1);
        assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("job_institution_failed")).toList())
                .singleElement().asString().startsWith("job_institution_failed task=" + IDEMPOTENCY_TASK
                        + " institution=" + REFUSED + " exception=");
    }

    private static long remaining(UUID institution) {
        return PurgeProbeRows.count("shared_idempotency_key", institution)
                + PurgeProbeRows.count("identity_login_backoff", institution)
                + PurgeProbeRows.count("identity_mfa_totp_backoff", institution);
    }

    private static long scheduled(String... tasks) {
        return PurgeProbeRows.scalar("select count(*) from scheduled_tasks where task_name in ("
                + "?, ".repeat(tasks.length - 1) + "?)", (Object[]) tasks);
    }

    /** Moves the next occurrence of the tasks into the past, so the next poll takes them. */
    private static void dueNow(String... tasks) {
        PurgeProbeRows.run("update scheduled_tasks set execution_time = now() - interval '1 second' "
                + "where picked = false and task_name in (" + "?, ".repeat(tasks.length - 1) + "?)",
                (Object[]) tasks);
    }
}
