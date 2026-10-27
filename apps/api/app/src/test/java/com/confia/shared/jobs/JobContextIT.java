package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.jobs.SchedulingFixtures.Probe;
import com.confia.shared.security.TransactionRunner;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The context a handler runs with (background-jobs-with-db-scheduler design.md decision 10; scenarios
 * BJ16, BJ17, BJ18 and BJ57): the system actor, inside the institution of the task, with the MDC of
 * {@code docs/07} section 3.3; a task of an institution that does not exist has no effect; and a
 * handler sees only the data of its own institution.
 */
class JobContextIT extends WorkerJobIT {

    private static final UUID SECOND_INSTITUTION = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    static final JobType<Probe> CONTEXT_PROBE = fastType("identity.context-probe", Probe.class, 1);
    static final Map<String, Map<String, String>> MDC_SEEN = new ConcurrentHashMap<>();
    static final Map<String, Long> VISIBLE_AUDIT_ROWS = new ConcurrentHashMap<>();

    @Configuration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        JobDefinition contextProbe(TransactionRunner runner, AuditLogWriter audit, DSLContext dsl) {
            return new OneTimeJob<>(CONTEXT_PROBE, (payload, context) -> {
                MDC_SEEN.put(context.instance().value(), MDC.getCopyOfContextMap());
                runner.execute(context.systemContext(), () -> {
                    audit.append(new AuditEntry(context.meta().institutionId(), null, "system", "context probe",
                            null, null, context.meta().requestId(), null, "identity.context_probe.ran",
                            "identity.context_probe", payload.accountId().toString(), "success", null, null, null,
                            null));
                    VISIBLE_AUDIT_ROWS.put(context.instance().value(),
                            dsl.fetchOne("select count(*) from shared_audit_log").get(0, Long.class));
                    return null;
                });
            });
        }
    }

    private static List<String[]> auditRows(UUID institution) {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select actor_kind, request_id::text, action from shared_audit_log "
                                + "where institution_id = ?")) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                List<String[]> rows = new java.util.ArrayList<>();
                while (result.next()) {
                    rows.add(new String[] {result.getString(1), result.getString(2), result.getString(3)});
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void seedAuditRow(UUID institution) {
        UUID request = UUID.randomUUID();
        transactionRunner().execute(SchedulingFixtures.contextOf(institution, request), () -> {
            new com.confia.shared.infrastructure.JooqAuditLogWriter(dsl)
                    .append(SchedulingFixtures.auditEntryOf(institution, request));
            return null;
        });
    }

    @Test
    void theHandlerRunsAsTheSystemActorInTheInstitutionOfTheTaskWithTheMdcAndSeesOnlyItsOwnRows() {
        seedAuditRow(SchedulingFixtures.INSTITUTION);
        seedAuditRow(SECOND_INSTITUTION);
        seedAuditRow(SECOND_INSTITUTION);
        UUID account = UUID.randomUUID();
        JobInstanceId instance;
        try (ConfigurableApplicationContext worker =
                WorkerHarness.start(Fixtures.class, SchedulingFixtures.INSTITUTION)) {
            instance = schedule(CONTEXT_PROBE, new Probe(account), SchedulingFixtures.INSTITUTION);
            Eventually.within(Duration.ofSeconds(90), () -> assertThat(rowOf(instance)).isNull());
        }

        Map<String, String> mdc = MDC_SEEN.get(instance.value());
        assertThat(mdc).containsEntry("jobTask", "identity.context-probe")
                .containsEntry("jobInstance", instance.value()).containsEntry("actorKind", "system")
                .containsEntry("institutionId", SchedulingFixtures.INSTITUTION.toString())
                .containsKeys("correlationId", "requestId");
        List<String[]> written = auditRows(SchedulingFixtures.INSTITUTION).stream()
                .filter(row -> row[2].equals("identity.context_probe.ran")).toList();
        assertThat(written).hasSize(1);
        assertThat(written.get(0)[0]).as("the actor of the entry is the system").isEqualTo("system");
        assertThat(VISIBLE_AUDIT_ROWS.get(instance.value()))
                .as("the handler sees exactly the rows of its own institution (the chain genesis, the seeded one and its own)")
                .isEqualTo((long) auditRows(SchedulingFixtures.INSTITUTION).size());
        assertThat(auditRows(SECOND_INSTITUTION)).as("the other institution is untouched").hasSize(2);
    }

    @Test
    void aTaskOfAnInstitutionThatDoesNotExistLeavesNoRowOfBusinessAndNoEffectInTheOthers() {
        seedAuditRow(SchedulingFixtures.INSTITUTION);
        UUID missing = UUID.fromString("00000000-0000-0000-0000-00000000ffff");
        int before = auditRows(SchedulingFixtures.INSTITUTION).size();
        JobInstanceId instance;
        try (ConfigurableApplicationContext worker =
                WorkerHarness.start(Fixtures.class, SchedulingFixtures.INSTITUTION)) {
            instance = schedule(CONTEXT_PROBE, new Probe(UUID.randomUUID()), missing);
            Eventually.within(Duration.ofSeconds(90), () -> assertThat(rowOf(instance)).isNull());
        }

        assertThat(VISIBLE_AUDIT_ROWS.get(instance.value())).as("it saw nothing of other institutions")
                .isEqualTo((long) auditRows(missing).size());
        assertThat(auditRows(SchedulingFixtures.INSTITUTION)).as("no entry was added to another institution")
                .hasSize(before);
    }
}
