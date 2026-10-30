package com.confia.identity.application;

import com.confia.identity.infrastructure.JooqIdentityRetentionPurges;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.jobs.RecurringRun;
import com.confia.shared.observability.events.OperationalEventLog;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the integration tests of the identity purge jobs share (background-jobs-with-db-scheduler
 * design.md decision 17): the real use cases over the real adapter, a clock fixed at an instant a test
 * chooses, the audit entries and the rows of an institution read inside that institution, and bulk
 * seeding by SQL. Tests that anchor a boundary read the database clock once ({@link #databaseNow()}) and
 * fix the use case's clock at that instant, so the cutoff the use case passes and the row instants are
 * exact to the microsecond and no clock of Java is compared with the one of PostgreSQL.
 */
abstract class IdentityPurgeFixtures extends RotationIntegrationTest {

    /** An institution the use case does not act on, to prove the purge never crosses to it. */
    final InstitutionId otherInstitution = new InstitutionId(UUID.randomUUID());

    private static final JsonMapper DETAIL_MAPPER = JsonMapper.builder().build();

    /**
     * The detail of an entry as a tree. The column is {@code jsonb}, so what is stored is the document
     * of {@code AuditJson} with its members reordered and spaced by the database: compare trees, never text.
     */
    static JsonNode detail(String json) {
        return DETAIL_MAPPER.readTree(json);
    }

    static JsonNode detail(AuditRowSnapshot entry) {
        return detail(entry.afterValue());
    }

    /** {@code now()} of PostgreSQL, read once. */
    static Instant databaseNow() {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("select now()");
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getObject(1, OffsetDateTime.class).toInstant();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    PurgeExpiredPasswordResetTokens resetPurge(Clock clock) {
        return resetPurge(new JooqIdentityRetentionPurges(dsl), new JooqAuditLogWriter(dsl), clock);
    }

    PurgeExpiredPasswordResetTokens resetPurge(IdentityRetentionPurges purges, AuditLogWriter audit,
            Clock clock) {
        return new PurgeExpiredPasswordResetTokens(transactionRunner(), purges, audit, new OperationalEventLog(),
                clock);
    }

    PurgeExpiredRefreshTokens refreshPurge(Clock clock) {
        return refreshPurge(new JooqIdentityRetentionPurges(dsl), new JooqAuditLogWriter(dsl), clock);
    }

    PurgeExpiredRefreshTokens refreshPurge(IdentityRetentionPurges purges, AuditLogWriter audit,
            Clock clock) {
        return new PurgeExpiredRefreshTokens(transactionRunner(), purges, audit, new OperationalEventLog(), clock);
    }

    /** One occurrence of a recurring job, with a run id of its own. */
    static RecurringRun runOf(String task) {
        return new RecurringRun(task, UUID.randomUUID(), Instant.parse("2026-10-25T10:30:00Z"));
    }

    static Clock fixedAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    /** An account with no password worth the name: only a parent for the rows the purge tests seed. */
    UUID seedPlainAccount(InstitutionId institution) {
        UUID account = UUID.randomUUID();
        transactionRunner().execute(contextOf(institution), () -> dsl.execute("""
                insert into identity_staff_account
                    (institution_id, id, email, password_hash, mfa_required)
                values (?, ?, ?, '$argon2id$v=19$m=19456,t=3,p=1$c2FsdA$aGFzaA', false)
                """, institution.value(), account, "purga." + account + "@colegio.edu.hn"));
        return account;
    }

    /** {@code count} tokens that expired at {@code expiresAt}; superseded, so none is an open token. */
    void seedResetTokens(InstitutionId institution, UUID account, int count, Instant expiresAt) {
        transactionRunner().execute(contextOf(institution), () -> dsl.execute("""
                insert into identity_password_reset_token
                    (institution_id, id, account_id, token_hash, issued_at, expires_at, superseded_at)
                select ?, gen_random_uuid(), ?,
                       replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', ''),
                       cast(? as timestamptz) - interval '30 minutes', cast(? as timestamptz),
                       cast(? as timestamptz) - interval '30 minutes'
                  from generate_series(1, ?) g
                """, institution.value(), account, expiresAt.toString(), expiresAt.toString(),
                expiresAt.toString(), count));
    }

    /**
     * A token consumed at {@code consumedAt} whose encrypted successor is still in its row, with its
     * family, its account and the open successor token. Returns the consumed token.
     */
    UUID seedConsumedWithResidualSuccessor(InstitutionId institution, Instant consumedAt) {
        UUID account = seedPlainAccount(institution);
        UUID family = UUID.randomUUID();
        UUID consumed = UUID.randomUUID();
        UUID successor = UUID.randomUUID();
        String at = consumedAt.toString();
        transactionRunner().execute(contextOf(institution), () -> {
            dsl.execute("""
                    insert into identity_refresh_token_family
                        (institution_id, id, account_id, second_factor_verified,
                         user_agent_fingerprint, created_at, last_used_at, absolute_expires_at)
                    values (?, ?, ?, false, ?, cast(? as timestamptz) - interval '1 hour',
                            cast(? as timestamptz), cast(? as timestamptz) + interval '1 day')
                    """, institution.value(), family, account, "0".repeat(64), at, at, at);
            dsl.execute("""
                    insert into identity_refresh_token
                        (institution_id, id, family_id, token_hash, issued_at, expires_at,
                         consumed_at, consumed_user_agent_fingerprint, replaced_by,
                         successor_token_ciphertext)
                    values (?, ?, ?,
                            replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', ''),
                            cast(? as timestamptz) - interval '1 hour',
                            cast(? as timestamptz) + interval '8 hours', cast(? as timestamptz), ?, ?,
                            'v1:AAAA')
                    """, institution.value(), consumed, family, at, at, at, "0".repeat(64), successor);
            return dsl.execute("""
                    insert into identity_refresh_token
                        (institution_id, id, family_id, token_hash, issued_at, expires_at)
                    values (?, ?, ?,
                            replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', ''),
                            cast(? as timestamptz), cast(? as timestamptz) + interval '8 hours')
                    """, institution.value(), successor, family, at, at);
        });
        return consumed;
    }

    /** The encrypted successor stored in the row of a token, or {@code null} once it was emptied. */
    String residualSuccessorOf(InstitutionId institution, UUID token) {
        return transactionRunner().execute(contextOf(institution), () -> dsl.fetchSingle("""
                select successor_token_ciphertext from identity_refresh_token
                 where institution_id = ? and id = ?
                """, institution.value(), token).get(0, String.class));
    }

    long count(InstitutionId institution, String table) {
        return transactionRunner().execute(contextOf(institution), () -> dsl.fetchSingle(
                "select count(*) from " + checked(table) + " where institution_id = ?",
                institution.value()).get(0, Long.class));
    }

    /** The audit entries of an institution with this action, oldest first. */
    List<AuditRowSnapshot> entries(InstitutionId institution, String action) {
        return transactionRunner().execute(contextOf(institution),
                () -> new JooqAuditLogReader(dsl).pageOf(institution, 0, 1000)).stream()
                .filter(row -> row.action().equals(action)).toList();
    }

    /**
     * The port, recording every call. Built over the real adapter it passes the calls through; built over
     * a script it touches no table and answers with the count the script gives for that call number
     * (from 1), which is how a test makes 200 batches come back full without a million rows.
     */
    static final class RecordingPurges implements IdentityRetentionPurges {

        /** One call: which operation, the cutoff it was given and the limit (zero for the clearing). */
        record Call(String operation, Instant cutoff, int limit) { }

        final List<Call> calls = new java.util.ArrayList<>();
        private final IdentityRetentionPurges real;
        private final java.util.function.IntToLongFunction script;

        RecordingPurges(IdentityRetentionPurges real) {
            this.real = real;
            this.script = null;
        }

        RecordingPurges(java.util.function.IntToLongFunction script) {
            this.real = null;
            this.script = script;
        }

        @Override
        public long purgePasswordResetTokens(InstitutionId institution, Instant cutoff, int limit) {
            calls.add(new Call("passwordResetTokens", cutoff, limit));
            return real != null ? real.purgePasswordResetTokens(institution, cutoff, limit)
                    : script.applyAsLong(calls.size());
        }

        @Override
        public RefreshTokenPurgeCount purgeRefreshTokenFamilies(InstitutionId institution,
                Instant cutoff, int limit) {
            calls.add(new Call("refreshTokenFamilies", cutoff, limit));
            if (real != null) {
                return real.purgeRefreshTokenFamilies(institution, cutoff, limit);
            }
            long families = script.applyAsLong(calls.size());
            return new RefreshTokenPurgeCount(families, families * 2);
        }

        @Override
        public long clearResidualSuccessors(InstitutionId institution, Instant cutoff) {
            calls.add(new Call("residualSuccessors", cutoff, 0));
            return real != null ? real.clearResidualSuccessors(institution, cutoff) : 0;
        }
    }

    /** A fixture table name is one of ours; this keeps a typo from reaching the statement. */
    private static String checked(String table) {
        if (!List.of("identity_password_reset_token", "identity_refresh_token_family",
                "identity_refresh_token").contains(table)) {
            throw new IllegalArgumentException(table);
        }
        return table;
    }
}
