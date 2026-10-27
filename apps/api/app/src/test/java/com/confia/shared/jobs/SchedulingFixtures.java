package com.confia.shared.jobs;

import com.confia.shared.audit.AuditEntry;
import com.confia.shared.security.SecurityContext;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

/**
 * What the scheduling integration tests share: a job type that obeys the payload rule, the security
 * context and the metadata of a transaction in one institution, an audit entry written in that same
 * transaction, and the reads of {@code scheduled_tasks} and {@code shared_audit_log} made as the
 * container's superuser, which bypasses row-level security and therefore sees what the business
 * transaction itself would not.
 */
final class SchedulingFixtures {

    /** The payload of the probe job: an internal identifier and nothing else. */
    record Probe(UUID accountId) { }

    static final JobType<Probe> TYPE = new JobType<>("identity.scheduling-probe", Probe.class,
            new RetryPolicy(3, Duration.ofSeconds(30), 2.0, Duration.ofMinutes(5)));

    static final UUID INSTITUTION = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    static final UUID OTHER_INSTITUTION = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    private SchedulingFixtures() {
    }

    static SecurityContext contextOf(UUID institution, UUID request) {
        return new SecurityContext("", "system", institution.toString(), request.toString());
    }

    static JobMeta metaOf(UUID institution, UUID request) {
        return new JobMeta(UUID.randomUUID(), request, null, ActorKind.STAFF, institution, null);
    }

    static JobInstanceId instanceOf(UUID institution, UUID account, UUID request) {
        return JobInstanceId.derive(TYPE.name(), institution, account, request);
    }

    /** The audit entry of a business operation that scheduled the job, written in the same transaction. */
    static AuditEntry auditEntryOf(UUID institution, UUID request) {
        return new AuditEntry(institution, null, "system", "scheduling probe", null, null, request, null,
                "identity.scheduling_probe.requested", "identity.scheduling_probe", request.toString(), "success",
                null, null, null, null);
    }

    static long tasksOf(JobInstanceId instance) {
        return count("select count(*) from scheduled_tasks where task_name = ? and task_instance = ?",
                TYPE.name(), instance.value());
    }

    static long tasks() {
        return count("select count(*) from scheduled_tasks");
    }

    static long auditRowsOf(UUID institution, UUID request) {
        return count("select count(*) from shared_audit_log where institution_id = ? and request_id = ?",
                institution, request);
    }

    /** The {@code task_data} of the only row of {@code instance}, as UTF-8 text. */
    static String taskDataOf(JobInstanceId instance) {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select convert_from(task_data, 'UTF8') from scheduled_tasks "
                                + "where task_name = ? and task_instance = ?")) {
            statement.setString(1, TYPE.name());
            statement.setString(2, instance.value());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("no task for the instance");
                }
                return result.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long count(String sql, Object... parameters) {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
