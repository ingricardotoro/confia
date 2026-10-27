package com.confia.shared.jobs;

import static confia.generated.jooq.tables.SharedAuditLog.SHARED_AUDIT_LOG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.jobs.SchedulingFixtures.Probe;
import com.confia.shared.jobs.infrastructure.JobRunner;
import com.confia.shared.jobs.infrastructure.TracedJobSerializer;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

/**
 * Every job type produces one effect even when it runs twice, as the at-least-once delivery of the
 * library allows (ADR-0016 compliance 5; scenarios BJ19 and BJ20). {@link #assertSingleEffect} runs
 * the handler of a definition twice with the same data through the real {@link JobRunner} and compares
 * the state it leaves with the state of one run.
 *
 * <p>The production worker registers no one-time job until the password-reset issuance arrives, so
 * the guard of non-vacuity is applied to the set under test here; that change adds its own definition
 * to the set and this check evaluates it.
 */
class HandlerIdempotenceIT extends CommittingPostgresIntegrationTest {

    private static final JobType<Probe> IDEMPOTENT = WorkerJobIT.fastType("identity.idempotent-probe", Probe.class, 2);
    private static final JobType<Probe> DUPLICATING = WorkerJobIT.fastType("identity.duplicating-probe", Probe.class, 2);

    private final JobRunner runner = new JobRunner(new TracedJobSerializer());

    private long auditRowsOf(UUID request) {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from shared_audit_log where request_id = ?")) {
            statement.setObject(1, request);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs {@code job} once on one request and twice on another, and compares the effect of both. */
    private void assertSingleEffect(OneTimeJob<Probe> job) {
        UUID onceRequest = UUID.randomUUID();
        UUID twiceRequest = UUID.randomUUID();
        for (UUID request : List.of(onceRequest, twiceRequest)) {
            int runs = request.equals(onceRequest) ? 1 : 2;
            for (int attempt = 1; attempt <= runs; attempt++) {
                runner.run(job, JobInstanceId.derive(job.name(), request).value(),
                        new TracedJob<>(new Probe(request),
                                SchedulingFixtures.metaOf(SchedulingFixtures.INSTITUTION, request)), attempt);
            }
        }
        assertThat(auditRowsOf(twiceRequest)).as("%s: two runs leave the effect of one", job.name())
                .isEqualTo(auditRowsOf(onceRequest));
    }

    private OneTimeJob<Probe> idempotent() {
        return new OneTimeJob<>(IDEMPOTENT, (payload, context) -> transactionRunner().execute(
                context.systemContext(), () -> {
                    AuditLogWriter writer = new com.confia.shared.infrastructure.JooqAuditLogWriter(dsl);
                    boolean already = dsl.fetchExists(DSL.selectOne().from(SHARED_AUDIT_LOG)
                            .where(SHARED_AUDIT_LOG.REQUEST_ID.eq(payload.accountId())));
                    if (!already) {
                        writer.append(entryFor(context, payload));
                    }
                    return null;
                }));
    }

    private OneTimeJob<Probe> duplicating() {
        return new OneTimeJob<>(DUPLICATING, (payload, context) -> transactionRunner().execute(
                context.systemContext(), () -> {
                    new com.confia.shared.infrastructure.JooqAuditLogWriter(dsl).append(entryFor(context, payload));
                    return null;
                }));
    }

    private static AuditEntry entryFor(JobContext context, Probe payload) {
        return new AuditEntry(context.meta().institutionId(), null, "system", "idempotence probe", null, null,
                payload.accountId(), null, "identity.idempotence_probe.ran", "identity.idempotence_probe",
                payload.accountId().toString(), "success", null, null, null, null);
    }

    @Test
    void anIdempotentHandlerLeavesOneEffectAfterTwoRuns() {
        List<OneTimeJob<Probe>> evaluated = List.of(idempotent());

        assertThat(evaluated).as("non-vacuous: at least one definition is evaluated").isNotEmpty();
        evaluated.forEach(this::assertSingleEffect);
    }

    @Test
    void aHandlerThatDuplicatesItsEffectMakesTheCheckFail() {
        assertThatThrownBy(() -> assertSingleEffect(duplicating())).isInstanceOf(AssertionError.class)
                .hasMessageContaining("identity.duplicating-probe");
    }
}
