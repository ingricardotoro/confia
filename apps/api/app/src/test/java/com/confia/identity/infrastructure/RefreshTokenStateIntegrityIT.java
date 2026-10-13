package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The integrity rules that V8 enforces inside the engine, on top of the shape of the two
 * refresh-token tables (session-tokens-and-web-layer design.md decision 8): a successor lives in
 * the same family as its predecessor and is never the predecessor itself, state only moves
 * forward (a revocation or a consumption is final, {@code last_used_at} never goes back, a cleared
 * successor ciphertext stays cleared), and the temporal and address checks added after the review
 * of the migration.
 *
 * <p>Statements run as the real {@code confia_admin_app} role. Every rejection is asserted with
 * its SQLState <em>and</em> the name of the constraint or trigger rule that fired, so a statement
 * that fails for an unrelated reason cannot pass for the wrong one. Each expected failure runs
 * behind a savepoint so that the surrounding transaction survives it.
 */
class RefreshTokenStateIntegrityIT extends CommittingPostgresIntegrationTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private static final String ZERO_FINGERPRINT = "0".repeat(64);
    private static final String OTHER_FINGERPRINT = "1".repeat(64);

    private static final String PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";

    private static final String ACCOUNT_INSERT = """
            insert into identity_staff_account
                (institution_id, id, email, password_hash, mfa_required)
            values (?, ?, ?, ?, false)
            """;

    private static final String FAMILY_INSERT = """
            insert into identity_refresh_token_family
                (institution_id, id, account_id, second_factor_verified, user_agent_fingerprint,
                 ip_first_seen, created_at, last_used_at, absolute_expires_at)
            values (?, ?, ?, false, ?, cast(? as inet), now(), now(), now() + interval '12 hours')
            """;

    private static final String TOKEN_INSERT = """
            insert into identity_refresh_token
                (institution_id, id, family_id, token_hash, issued_at, expires_at)
            values (?, ?, ?, ?, now(), now() + interval '8 hours')
            """;

    private static final String ROTATE = """
            update identity_refresh_token
               set consumed_at = now(), consumed_user_agent_fingerprint = ?, replaced_by = ?,
                   successor_token_ciphertext = 'v1:AAAA'
             where institution_id = ? and id = ?
            """;

    private static final String REVOKE = """
            update identity_refresh_token_family
               set revoked_at = now(), revoked_reason = ?
             where institution_id = ? and id = ?
            """;

    private static final String SET_LAST_USED = """
            update identity_refresh_token_family set last_used_at = now() + ?::interval
             where institution_id = ? and id = ?
            """;

    private static final String CLEAR_CIPHERTEXT = """
            update identity_refresh_token set successor_token_ciphertext = null
             where institution_id = ? and id = ?
            """;

    private static final String FAMILY_FINAL = "identity_refresh_token_family_revocation_final";
    private static final String FAMILY_FORWARD = "identity_refresh_token_family_last_used_forward";
    private static final String CONSUMPTION_FINAL = "identity_refresh_token_consumption_final";
    private static final String CIPHERTEXT_FORWARD = "identity_refresh_token_ciphertext_forward";

    @Test
    void aSuccessorMustLiveInTheSameFamilyAsItsPredecessor() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session()) {
            Seeded family = session.seedFamilyWithOpenToken(institution);
            Seeded otherFamily = session.seedFamilyWithOpenToken(institution);

            session.execute(ROTATE, ZERO_FINGERPRINT, otherFamily.tokenId(), institution,
                    family.tokenId());

            assertThatThrownBy(session::commit)
                    .as("replaced_by names a real token of the same institution, but of another"
                            + " family: only a composite key including the family can refuse it")
                    .isInstanceOf(SQLException.class)
                    .satisfies(thrown -> {
                        assertThat(((SQLException) thrown).getSQLState())
                                .isEqualTo(FOREIGN_KEY_VIOLATION);
                        assertThat(thrown).hasMessageContaining(
                                "identity_refresh_token_successor_fk");
                    });
        }
    }

    @Test
    void aSuccessorOfTheSameFamilyIsAccepted() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session()) {
            Seeded seeded = session.seedFamilyWithOpenToken(institution);
            UUID successor = UUID.randomUUID();

            session.execute(ROTATE, ZERO_FINGERPRINT, successor, institution, seeded.tokenId());
            session.execute(TOKEN_INSERT, institution, successor, seeded.familyId(),
                    someHash());

            session.run("set constraints all immediate");
            assertThat(session.count("identity_refresh_token", institution)).isEqualTo(2L);
        }
    }

    @Test
    void aTokenCannotBeItsOwnSuccessor() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session()) {
            Seeded seeded = session.seedFamilyWithOpenToken(institution);

            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_not_self_successor_chk",
                    ROTATE, ZERO_FINGERPRINT, seeded.tokenId(), institution, seeded.tokenId());
        }
    }

    @Test
    void aRevokedFamilyCannotBeUnrevokedOrChangeItsReason() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session()) {
            Seeded seeded = session.seedFamilyWithOpenToken(institution);
            session.execute(REVOKE, "reuse_detected", institution, seeded.familyId());

            session.assertRejected(CHECK_VIOLATION, FAMILY_FINAL, """
                    update identity_refresh_token_family
                       set revoked_at = null, revoked_reason = null
                     where institution_id = ? and id = ?
                    """, institution, seeded.familyId());
            session.assertRejected(CHECK_VIOLATION, FAMILY_FINAL, """
                    update identity_refresh_token_family set revoked_at = null
                     where institution_id = ? and id = ?
                    """, institution, seeded.familyId());
            session.assertRejected(CHECK_VIOLATION, FAMILY_FINAL, REVOKE, "password_change",
                    institution, seeded.familyId());
            session.assertRejected(CHECK_VIOLATION, FAMILY_FINAL, """
                    update identity_refresh_token_family
                       set revoked_at = now() + interval '1 minute'
                     where institution_id = ? and id = ?
                    """, institution, seeded.familyId());
        }
    }

    @Test
    void touchingARevokedFamilyWithoutChangingItsRevocationIsAccepted() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session()) {
            Seeded seeded = session.seedFamilyWithOpenToken(institution);
            session.execute(REVOKE, "password_change", institution, seeded.familyId());

            assertThat(session.execute("""
                    update identity_refresh_token_family
                       set revoked_at = revoked_at, revoked_reason = revoked_reason,
                           last_used_at = last_used_at
                     where institution_id = ? and id = ?
                    """, institution, seeded.familyId()))
                    .as("rewriting the same values is not a change of state")
                    .isEqualTo(1);
        }
    }

    @Test
    void lastUsedAtNeverMovesBackwardsButMayAdvance() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session()) {
            Seeded seeded = session.seedFamilyWithOpenToken(institution);

            assertThat(session.execute(SET_LAST_USED, "10 minutes", institution,
                    seeded.familyId())).as("moving forward is accepted").isEqualTo(1);
            assertThat(session.execute(SET_LAST_USED, "10 minutes", institution,
                    seeded.familyId())).as("staying put is accepted").isEqualTo(1);
            assertThat(session.execute(SET_LAST_USED, "20 minutes", institution,
                    seeded.familyId())).as("moving forward again is accepted").isEqualTo(1);

            session.assertRejected(CHECK_VIOLATION, FAMILY_FORWARD, SET_LAST_USED, "19 minutes",
                    institution, seeded.familyId());
            session.assertRejected(CHECK_VIOLATION, FAMILY_FORWARD, SET_LAST_USED, "0 minutes",
                    institution, seeded.familyId());
        }
    }

    @Test
    void aConsumedTokenCannotBeReopenedOrRedirected() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session()) {
            Seeded seeded = session.seedFamilyWithOpenToken(institution);
            UUID successor = UUID.randomUUID();
            session.execute(ROTATE, ZERO_FINGERPRINT, successor, institution, seeded.tokenId());
            session.execute(TOKEN_INSERT, institution, successor, seeded.familyId(),
                    someHash());
            session.run("set constraints all immediate");

            session.assertRejected(CHECK_VIOLATION, CONSUMPTION_FINAL, """
                    update identity_refresh_token
                       set consumed_at = null, consumed_user_agent_fingerprint = null,
                           replaced_by = null
                     where institution_id = ? and id = ?
                    """, institution, seeded.tokenId());
            session.assertRejected(CHECK_VIOLATION, CONSUMPTION_FINAL, """
                    update identity_refresh_token set consumed_at = null
                     where institution_id = ? and id = ?
                    """, institution, seeded.tokenId());
            session.assertRejected(CHECK_VIOLATION, CONSUMPTION_FINAL, """
                    update identity_refresh_token set replaced_by = null
                     where institution_id = ? and id = ?
                    """, institution, seeded.tokenId());
            session.assertRejected(CHECK_VIOLATION, CONSUMPTION_FINAL, """
                    update identity_refresh_token set consumed_user_agent_fingerprint = null
                     where institution_id = ? and id = ?
                    """, institution, seeded.tokenId());
            session.assertRejected(CHECK_VIOLATION, CONSUMPTION_FINAL, """
                    update identity_refresh_token set replaced_by = ?
                     where institution_id = ? and id = ?
                    """, UUID.randomUUID(), institution, seeded.tokenId());
            session.assertRejected(CHECK_VIOLATION, CONSUMPTION_FINAL, """
                    update identity_refresh_token set consumed_user_agent_fingerprint = ?
                     where institution_id = ? and id = ?
                    """, OTHER_FINGERPRINT, institution, seeded.tokenId());
            session.assertRejected(CHECK_VIOLATION, CONSUMPTION_FINAL, """
                    update identity_refresh_token
                       set consumed_at = consumed_at + interval '1 second'
                     where institution_id = ? and id = ?
                    """, institution, seeded.tokenId());
        }
    }

    @Test
    void theSuccessorCiphertextCannotBeRewrittenButMayBeClearedOnceAndStaysCleared()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session()) {
            Seeded seeded = session.seedFamilyWithOpenToken(institution);
            UUID successor = UUID.randomUUID();
            session.execute(ROTATE, ZERO_FINGERPRINT, successor, institution, seeded.tokenId());
            session.execute(TOKEN_INSERT, institution, successor, seeded.familyId(),
                    someHash());

            String rewrite = """
                    update identity_refresh_token set successor_token_ciphertext = ?
                     where institution_id = ? and id = ?
                    """;
            session.assertRejected(CHECK_VIOLATION, CIPHERTEXT_FORWARD, rewrite, "v1:BBBB",
                    institution, seeded.tokenId());
            assertThat(session.execute(rewrite, "v1:AAAA", institution, seeded.tokenId()))
                    .as("writing the same value again changes nothing and is accepted")
                    .isEqualTo(1);

            assertThat(session.execute(CLEAR_CIPHERTEXT, institution, seeded.tokenId()))
                    .as("the grace window closes: clearing the ciphertext is accepted")
                    .isEqualTo(1);
            assertThat(session.execute(CLEAR_CIPHERTEXT, institution, seeded.tokenId()))
                    .as("clearing an already cleared ciphertext is a no-op").isEqualTo(1);

            session.assertRejected(CHECK_VIOLATION, CIPHERTEXT_FORWARD, rewrite, "v1:AAAA",
                    institution, seeded.tokenId());
            session.assertRejected(CHECK_VIOLATION, CIPHERTEXT_FORWARD, rewrite, "v1:CCCC",
                    institution, seeded.tokenId());
        }
    }

    @Test
    void rotationFollowedByClearingTheGraceSuccessorIsAccepted() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session()) {
            Seeded seeded = session.seedFamilyWithOpenToken(institution);
            UUID successor = UUID.randomUUID();

            assertThat(session.execute(ROTATE, ZERO_FINGERPRINT, successor, institution,
                    seeded.tokenId())).as("consume with the encrypted successor").isEqualTo(1);
            assertThat(session.execute(TOKEN_INSERT, institution, successor, seeded.familyId(),
                    someHash())).isEqualTo(1);
            assertThat(session.execute(SET_LAST_USED, "1 minute", institution,
                    seeded.familyId())).isEqualTo(1);
            session.run("set constraints all immediate");

            assertThat(session.execute(CLEAR_CIPHERTEXT, institution, seeded.tokenId()))
                    .as("the next use of the family clears the ciphertext").isEqualTo(1);
        }
    }

    @Test
    void revokingForReuseDetectionAndClearingTheSuccessorsIsAccepted() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session()) {
            Seeded seeded = session.seedFamilyWithOpenToken(institution);
            UUID successor = UUID.randomUUID();
            session.execute(ROTATE, ZERO_FINGERPRINT, successor, institution, seeded.tokenId());
            session.execute(TOKEN_INSERT, institution, successor, seeded.familyId(),
                    someHash());

            assertThat(session.execute(REVOKE, "reuse_detected", institution,
                    seeded.familyId())).isEqualTo(1);
            assertThat(session.execute(CLEAR_CIPHERTEXT, institution, seeded.tokenId()))
                    .isEqualTo(1);
            session.run("set constraints all immediate");
        }
    }

    @Test
    void revokingEveryLiveFamilyOfAnAccountForAPasswordChangeIsAccepted() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session()) {
            UUID accountId = session.seedAccount(institution);
            UUID first = session.insertFamily(institution, accountId);
            UUID second = session.insertFamily(institution, accountId);

            assertThat(session.execute("""
                    update identity_refresh_token_family
                       set revoked_at = now(), revoked_reason = 'password_change'
                     where institution_id = ? and account_id = ? and revoked_at is null
                    """, institution, accountId))
                    .as("both live families are revoked in one statement").isEqualTo(2);
            assertThat(first).isNotEqualTo(second);
        }
    }

    @Test
    void aRevocationCannotPrecedeTheCreationOfTheFamily() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session()) {
            Seeded seeded = session.seedFamilyWithOpenToken(institution);

            session.assertRejected(CHECK_VIOLATION,
                    "identity_refresh_token_family_revoked_after_created_chk", """
                    update identity_refresh_token_family
                       set revoked_at = now() - interval '1 minute',
                           revoked_reason = 'password_change'
                     where institution_id = ? and id = ?
                    """, institution, seeded.familyId());
            assertThat(session.execute(REVOKE, "password_change", institution,
                    seeded.familyId()))
                    .as("a revocation at the creation instant is accepted").isEqualTo(1);
        }
    }

    @Test
    void aConsumptionCannotPrecedeTheIssueOfTheToken() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session()) {
            Seeded seeded = session.seedFamilyWithOpenToken(institution);

            session.assertRejected(CHECK_VIOLATION,
                    "identity_refresh_token_consumed_after_issued_chk", """
                    update identity_refresh_token
                       set consumed_at = now() - interval '1 minute',
                           consumed_user_agent_fingerprint = ?, replaced_by = ?
                     where institution_id = ? and id = ?
                    """, ZERO_FINGERPRINT, UUID.randomUUID(), institution, seeded.tokenId());
        }
    }

    @Test
    void anEncryptedSuccessorNeedsContentAfterTheVersionPrefix() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session()) {
            Seeded seeded = session.seedFamilyWithOpenToken(institution);
            String insertCiphertext = """
                    update identity_refresh_token
                       set consumed_at = now(), consumed_user_agent_fingerprint = ?,
                           replaced_by = ?, successor_token_ciphertext = ?
                     where institution_id = ? and id = ?
                    """;

            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_successor_chk",
                    insertCiphertext, ZERO_FINGERPRINT, UUID.randomUUID(), "v1:", institution,
                    seeded.tokenId());
            assertThat(session.execute(insertCiphertext, ZERO_FINGERPRINT, UUID.randomUUID(),
                    "v1:A", institution, seeded.tokenId()))
                    .as("one character after the prefix is accepted").isEqualTo(1);
        }
    }

    @Test
    void theRecordedAddressMustBeAHostAddress() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session()) {
            UUID accountId = session.seedAccount(institution);

            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_family_ip_host_chk",
                    FAMILY_INSERT, institution, UUID.randomUUID(), accountId, ZERO_FINGERPRINT,
                    "192.0.2.0/24");
            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_family_ip_host_chk",
                    FAMILY_INSERT, institution, UUID.randomUUID(), accountId, ZERO_FINGERPRINT,
                    "2001:db8::/32");
            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_family_ip_host_chk",
                    FAMILY_INSERT, institution, UUID.randomUUID(), accountId, ZERO_FINGERPRINT,
                    "192.0.2.1/31");
            assertThat(session.execute(FAMILY_INSERT, institution, UUID.randomUUID(), accountId,
                    ZERO_FINGERPRINT, "192.0.2.1")).as("an IPv4 host").isEqualTo(1);
            assertThat(session.execute(FAMILY_INSERT, institution, UUID.randomUUID(), accountId,
                    ZERO_FINGERPRINT, "2001:db8::1")).as("an IPv6 host").isEqualTo(1);
            assertThat(session.execute(FAMILY_INSERT, institution, UUID.randomUUID(), accountId,
                    ZERO_FINGERPRINT, null)).as("no address at all").isEqualTo(1);
        }
    }

    /** 64 lowercase hex characters, the only thing {@code token_hash_chk} enforces. */
    private static String someHash() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    private record Seeded(UUID accountId, UUID familyId, UUID tokenId) {
    }

    /**
     * One real connection as {@code confia_admin_app}, in a manual transaction that rolls back on
     * close unless the test commits. Every expected failure runs behind a savepoint.
     */
    private static final class Session implements AutoCloseable {

        private final Connection connection;

        Session() throws SQLException {
            this.connection = SharedPostgresContainer.connectionAs("confia_admin_app");
            this.connection.setAutoCommit(false);
        }

        UUID seedAccount(UUID institutionId) throws SQLException {
            try (PreparedStatement statement = connection
                    .prepareStatement("select set_config('app.institution_id', ?, true)")) {
                statement.setString(1, institutionId.toString());
                statement.execute();
            }
            UUID accountId = UUID.randomUUID();
            execute(ACCOUNT_INSERT, institutionId, accountId, "state." + accountId
                    + "@colegio.edu.hn", PASSWORD_HASH);
            return accountId;
        }

        UUID insertFamily(UUID institutionId, UUID accountId) throws SQLException {
            UUID familyId = UUID.randomUUID();
            execute(FAMILY_INSERT, institutionId, familyId, accountId, ZERO_FINGERPRINT,
                    "192.0.2.1");
            return familyId;
        }

        Seeded seedFamilyWithOpenToken(UUID institutionId) throws SQLException {
            UUID accountId = seedAccount(institutionId);
            UUID familyId = insertFamily(institutionId, accountId);
            UUID tokenId = UUID.randomUUID();
            execute(TOKEN_INSERT, institutionId, tokenId, familyId, someHash());
            return new Seeded(accountId, familyId, tokenId);
        }

        int execute(String sql, Object... params) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                return statement.executeUpdate();
            }
        }

        void run(String sql) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.execute();
            }
        }

        long count(String table, UUID institutionId) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select count(*) from " + table + " where institution_id = ?")) {
                statement.setObject(1, institutionId);
                try (var resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getLong(1);
                }
            }
        }

        void commit() throws SQLException {
            connection.commit();
        }

        /**
         * Runs the statement behind a savepoint and requires it to fail with {@code sqlState} and
         * a message that names {@code messageFragment}. A statement that succeeds fails the
         * assertion.
         */
        void assertRejected(String sqlState, String messageFragment, String sql, Object... params)
                throws SQLException {
            Savepoint savepoint = connection.setSavepoint();
            SQLException failure = null;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                statement.execute();
            } catch (SQLException e) {
                failure = e;
            }
            if (failure == null) {
                throw new AssertionError("the database must refuse: " + sql.strip());
            }
            connection.rollback(savepoint);
            assertThat(failure.getSQLState()).as(failure.getMessage()).isEqualTo(sqlState);
            assertThat(failure.getMessage()).contains(messageFragment);
        }

        private static void bind(PreparedStatement statement, Object[] params)
                throws SQLException {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
        }

        @Override
        public void close() throws SQLException {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }
}
