package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PasswordResetTokenHash;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqPasswordResetTokenRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.jobs.ActorKind;
import com.confia.shared.jobs.JobContext;
import com.confia.shared.jobs.JobInstanceId;
import com.confia.shared.jobs.JobMeta;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;

/**
 * What the integration tests of the password-reset issuance job share (background-jobs-with-db-scheduler
 * design.md decision 18): the real handler over the real repositories, a sender that records, a
 * clock a test moves, and reads of the committed state made inside the institution they look at.
 * Nothing here names a real person: the addresses are fixtures of the school domain the project uses.
 */
abstract class PasswordResetIssuanceFixtures extends CommittingPostgresIntegrationTest {

    /** A fixed institution, so a test can name it on the command line of a deliberate break. */
    static final UUID INSTITUTION_A = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    static final UUID INSTITUTION_B = UUID.fromString("00000000-0000-0000-0000-00000000b002");

    static final String ANA = "ana.martinez@colegio.edu.hn";

    private final java.util.Map<UUID, Long> auditBaseline = new java.util.HashMap<>();

    protected final MutableClock clock = new MutableClock("2026-10-25T10:00:05Z");
    protected final RecordingSender sender = new RecordingSender();

    protected IssuePasswordResetToken issuance() {
        return new IssuePasswordResetToken(transactionRunner(), new JooqStaffAccountRepository(dsl),
                new JooqPasswordResetTokenRepository(dsl), sender, new JooqAuditLogWriter(dsl),
                new SecureRandom(), clock);
    }

    protected PasswordResetIssuanceJobHandler handler() {
        return new PasswordResetIssuanceJobHandler(issuance());
    }

    /** Runs the handler as the worker does: the institution of the task, a request of its own. */
    protected void run(UUID institution, UUID account) {
        handler().handle(new PasswordResetIssuanceJob(account), contextOf(institution, account, 1));
    }

    protected static JobContext contextOf(UUID institution, UUID account, int attempt) {
        UUID request = UUID.randomUUID();
        JobMeta meta = new JobMeta(request, request, null, ActorKind.STAFF, institution, null);
        return new JobContext(PasswordResetIssuanceJobs.TYPE.name(),
                JobInstanceId.derive(PasswordResetIssuanceJobs.TYPE.name(), institution, account, request),
                meta, attempt);
    }

    protected UUID seedAccount(UUID institution, String email) {
        UUID account = UUID.randomUUID();
        seedAccount(institution, account, email);
        return account;
    }

    protected void seedAccount(UUID institution, UUID account, String email) {
        transactionRunner().execute(contextFor(institution), () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, '$argon2id$v=19$m=19456,t=3,p=1$c2FsdA$aGFzaA', false)
                    """, institution, account, LoginIdentifier.of(email).value());
            return null;
        });
    }

    /** The application role cannot delete an account (nothing financial or personal is deleted by it), so the test does it as the owner. */
    protected void deleteAccount(UUID institution, UUID account) {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "delete from identity_staff_account where institution_id = ? and id = ?")) {
            statement.setObject(1, institution);
            statement.setObject(2, account);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The audit log is append-only and is never truncated between tests, so a test reads only the
     * entries written after it started.
     */
    @BeforeEach
    void rememberTheAuditLogPosition() throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres")) {
            for (UUID institution : List.of(INSTITUTION_A, INSTITUTION_B)) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "select coalesce(max(id), 0) from shared_audit_log where institution_id = ?")) {
                    statement.setObject(1, institution);
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        auditBaseline.put(institution, result.getLong(1));
                    }
                }
            }
        }
    }

    /** All the tokens of the account, read inside its institution. */
    protected long tokenCount(UUID institution, UUID account) {
        return count(institution, "select count(*) as c from identity_password_reset_token "
                + "where institution_id = ? and account_id = ?", account);
    }

    /** The tokens of the account that are neither consumed nor superseded. */
    protected long openTokenCount(UUID institution, UUID account) {
        return count(institution, "select count(*) as c from identity_password_reset_token "
                + "where institution_id = ? and account_id = ? "
                + "and consumed_at is null and superseded_at is null", account);
    }

    protected long tokenCountOfInstitution(UUID institution) {
        return count(institution, "select count(*) as c from identity_password_reset_token "
                + "where institution_id = ?");
    }

    private long count(UUID institution, String sql, UUID... extra) {
        return transactionRunner().execute(contextFor(institution), () -> {
            Object[] bindings = new Object[1 + extra.length];
            bindings[0] = institution;
            System.arraycopy(extra, 0, bindings, 1, extra.length);
            return dsl.fetchOne(sql, bindings).get("c", Number.class).longValue();
        });
    }

    protected boolean isOpen(UUID institution, PlainPasswordResetToken token) {
        return transactionRunner().execute(contextFor(institution),
                () -> new JooqPasswordResetTokenRepository(dsl)
                        .findByHash(new InstitutionId(institution), PasswordResetTokenHash.of(token))
                        .map(row -> row.consumedAt() == null && row.supersededAt() == null)
                        .orElseThrow());
    }

    protected boolean isSuperseded(UUID institution, PlainPasswordResetToken token) {
        return transactionRunner().execute(contextFor(institution),
                () -> new JooqPasswordResetTokenRepository(dsl)
                        .findByHash(new InstitutionId(institution), PasswordResetTokenHash.of(token))
                        .map(row -> row.supersededAt() != null)
                        .orElseThrow());
    }

    protected List<AuditRowSnapshot> auditRows(UUID institution) {
        return transactionRunner().execute(contextFor(institution),
                () -> new JooqAuditLogReader(dsl).pageOf(new InstitutionId(institution),
                        auditBaseline.getOrDefault(institution, 0L), 1000));
    }

    protected List<String> auditActions(UUID institution) {
        return auditRows(institution).stream().map(AuditRowSnapshot::action).toList();
    }

    protected static SecurityContext contextFor(UUID institution) {
        return new SecurityContext("", "system", institution.toString(), UUID.randomUUID().toString());
    }

    /** The reads below need a token to exist: this is the one the sender received last. */
    protected PlainPasswordResetToken lastSent() {
        assertThat(sender.sent).isNotEmpty();
        return sender.sent.get(sender.sent.size() - 1);
    }

    /** A clock a test moves; the zone is UTC. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(String instant) {
            this.now = Instant.parse(instant);
        }

        void set(String instant) {
            this.now = Instant.parse(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Records every call; delivers nothing. */
    static final class RecordingSender implements PasswordResetLinkSender {

        final List<PlainPasswordResetToken> sent = new ArrayList<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public void ensureAvailable() {
            calls.add("ensureAvailable");
        }

        @Override
        public void send(InstitutionId institutionId, StaffAccountId accountId,
                PlainPasswordResetToken token) {
            calls.add("send");
            sent.add(token);
        }
    }
}
