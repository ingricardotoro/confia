package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.identity.infrastructure.HmacLoginIdentifierFingerprinter;
import com.confia.identity.infrastructure.JobPasswordResetIssuanceScheduler;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.jobs.JobInstanceId;
import com.confia.shared.jobs.infrastructure.DbSchedulerJobScheduler;
import com.confia.shared.jobs.infrastructure.TracedJobSerializer;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionalDataSource;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link RequestPasswordReset} with the real job-scheduling adapter, through the real transaction
 * and a real PostgreSQL (background-jobs-with-db-scheduler design.md decision 18; scenarios I229,
 * I230 and I231): the task exists if and only if the request's own transaction commits, the same
 * transaction that writes the audit entry.
 */
class RequestPasswordResetSchedulingIT extends CommittingPostgresIntegrationTest {

    private static final String EXISTING_EMAIL = "ana.martinez@colegio.edu.hn";
    private static final String NONEXISTENT_EMAIL = "nadie@colegio.edu.hn";
    private static final String TASK_NAME = "identity.password-reset-issuance";
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    private static final HmacLoginIdentifierFingerprinter FINGERPRINTER = new HmacLoginIdentifierFingerprinter(
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(new byte[32])));

    private final InstitutionId institutionId = new InstitutionId(UUID.randomUUID());

    /** Scenario I229: one task, internal identifiers only, no token yet. */
    @Test
    void aCommittedRequestLeavesOneTaskWithInternalIdentifiersOnly() {
        UUID accountId = seedStaffAccount(EXISTING_EMAIL);
        UUID requestId = UUID.randomUUID();

        request(EXISTING_EMAIL, requestId, new JooqAuditLogWriter(dsl));

        JobInstanceId instance = JobInstanceId.derive(TASK_NAME, institutionId.value(), accountId, requestId);
        assertThat(tasksNamed()).isEqualTo(1);
        assertThat(tasksOf(instance)).isEqualTo(1);
        assertThat(taskDataOf(instance))
                .startsWith("{\"payload\":{\"accountId\":\"" + accountId + "\"},\"meta\":{")
                .contains("\"correlationId\":\"" + requestId + "\"", "\"requestId\":\"" + requestId + "\"",
                        "\"institutionId\":\"" + institutionId.value() + "\"", "\"actorKind\":\"STAFF\"")
                .doesNotContain(EXISTING_EMAIL, "ana.martinez");
        assertThat(instance.value()).doesNotContain("ana");
        assertThat(count("select count(*) from identity_password_reset_token where institution_id = ?",
                institutionId.value())).isZero();
        assertThat(count("select count(*) from shared_audit_log where institution_id = ? and request_id = ?",
                institutionId.value(), requestId)).isEqualTo(1);
    }

    /** Scenario I230: the failing audit writer rolls the transaction back, task included. */
    @Test
    void anAuditWriterThatFailsLeavesNeitherTheEntryNorTheTask() {
        seedStaffAccount(EXISTING_EMAIL);
        UUID requestId = UUID.randomUUID();
        AuditLogWriter failing = entry -> {
            throw new IllegalStateException("the audit write failed after the scheduling");
        };

        assertThatThrownBy(() -> request(EXISTING_EMAIL, requestId, failing))
                .isInstanceOf(IllegalStateException.class);

        assertThat(tasksNamed()).as("no task survives the rollback").isZero();
        assertThat(count("select count(*) from shared_audit_log where institution_id = ? and request_id = ?",
                institutionId.value(), requestId)).as("no audit entry either").isZero();
    }

    /** Scenario I230: an identifier with no account schedules nothing. */
    @Test
    void anIdentifierWithoutAnAccountSchedulesNothing() {
        seedStaffAccount(EXISTING_EMAIL);

        request(NONEXISTENT_EMAIL, UUID.randomUUID(), new JooqAuditLogWriter(dsl));

        assertThat(tasksNamed()).isZero();
    }

    /** Scenario I231: the same request twice is one task and no error; a new request is a second task. */
    @Test
    void theSameRequestSchedulesOneTaskAndANewRequestAnotherOne() {
        seedStaffAccount(EXISTING_EMAIL);
        UUID requestId = UUID.randomUUID();

        request(EXISTING_EMAIL, requestId, new JooqAuditLogWriter(dsl));
        request(EXISTING_EMAIL, requestId, entry -> { });

        assertThat(tasksNamed()).as("the repeated request found its instance").isEqualTo(1);

        request(EXISTING_EMAIL, UUID.randomUUID(), entry -> { });

        assertThat(tasksNamed()).as("a request with another id is another instance").isEqualTo(2);
    }

    private void request(String presentedIdentifier, UUID requestId, AuditLogWriter auditWriter) {
        PasswordResetIssuanceScheduler scheduler = new JobPasswordResetIssuanceScheduler(
                new DbSchedulerJobScheduler(new TransactionalDataSource(dataSource()), new TracedJobSerializer()),
                Clock.fixed(NOW, ZoneOffset.UTC));
        new RequestPasswordReset(transactionRunner(), () -> institutionId, new JooqStaffAccountRepository(dsl),
                FINGERPRINTER, scheduler, auditWriter)
                .execute(new SecurityContext("", "system", institutionId.value().toString(), requestId.toString()),
                        new RequestPasswordResetCommand(presentedIdentifier));
    }

    private UUID seedStaffAccount(String email) {
        UUID accountId = UUID.randomUUID();
        String hash = "$argon2id$v=19$m=19456,t=3,p=1$c2FsdA$aGFzaA";
        transactionRunner().execute(new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString()), () -> {
                    dsl.execute("""
                            insert into identity_staff_account
                                (institution_id, id, email, password_hash, mfa_required)
                            values (?, ?, ?, ?, false)
                            """, institutionId.value(), accountId, LoginIdentifier.of(email).value(), hash);
                    return null;
                });
        return accountId;
    }

    private static long tasksNamed() {
        return count("select count(*) from scheduled_tasks where task_name = ?", TASK_NAME);
    }

    private static long tasksOf(JobInstanceId instance) {
        return count("select count(*) from scheduled_tasks where task_name = ? and task_instance = ?",
                TASK_NAME, instance.value());
    }

    private static String taskDataOf(JobInstanceId instance) {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select convert_from(task_data, 'UTF8') from scheduled_tasks "
                                + "where task_name = ? and task_instance = ?")) {
            statement.setString(1, TASK_NAME);
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
