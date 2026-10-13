package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The refresh-token family and token tables of {@code V8} (session-tokens-and-web-layer design.md
 * decision 8; specs/build-integrity/spec.md scenarios BI01 to BI10, BI36 and BI37), exercised with
 * real statements as the real {@code confia_admin_app} role. {@code RolePrivilegeMatrixIT} reads
 * the catalogue; this class proves each constraint, policy and grant by making the database refuse
 * (or accept) a concrete statement.
 *
 * <p>Every rejection is asserted with its SQLState <em>and</em> the name of the constraint that
 * fired, so a statement that fails for an unrelated reason (a missing parent row, a missing grant)
 * cannot pass for the wrong one. Each statement runs behind a savepoint so the surrounding
 * transaction survives the expected failure.
 */
class RefreshTokenSchemaIT extends CommittingPostgresIntegrationTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

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

    private static final String CONSUME = """
            update identity_refresh_token
               set consumed_at = now(), consumed_user_agent_fingerprint = ?, replaced_by = ?
             where institution_id = ? and id = ?
            """;

    private static final String ZERO_FINGERPRINT = "0".repeat(64);

    @Test
    void oneInstitutionSeesOnlyItsOwnFamiliesAndTokensOnBothTables() throws SQLException {
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();
        try (Session session = new Session("confia_admin_app")) {
            Seeded a = session.seedFamilyWithOpenToken(institutionA);
            Seeded b = session.seedFamilyWithOpenToken(institutionB);

            session.contextOf(institutionB);
            assertThat(session.count("identity_refresh_token_family"))
                    .as("under B's context the unfiltered family count is B's one row, not A's")
                    .isEqualTo(1L);
            assertThat(session.count("identity_refresh_token"))
                    .as("under B's context the unfiltered token count is B's one row, not A's")
                    .isEqualTo(1L);
            assertThat(session.countWhere("identity_refresh_token_family", "id", a.familyId()))
                    .as("A's family physically exists but B cannot see it").isZero();
            assertThat(session.countWhere("identity_refresh_token", "id", a.tokenId())).isZero();
            assertThat(session.countWhere("identity_refresh_token_family", "id", b.familyId()))
                    .isEqualTo(1L);
            assertThat(session.countWhere("identity_refresh_token", "id", b.tokenId()))
                    .isEqualTo(1L);

            session.contextOf(institutionA);
            assertThat(session.count("identity_refresh_token_family")).isEqualTo(1L);
            assertThat(session.countWhere("identity_refresh_token_family", "id", b.familyId()))
                    .isZero();
        }
    }

    @Test
    void withoutAnInstitutionContextOrWithAnEmptyOneBothTablesReturnZeroRowsNotAnError()
            throws SQLException {
        try (Session session = new Session("confia_admin_app")) {
            session.seedFamilyWithOpenToken(UUID.randomUUID());
            assertThat(session.count("identity_refresh_token_family"))
                    .as("sanity: the seeded rows are visible under their own context")
                    .isEqualTo(1L);

            session.run("reset app.institution_id");
            assertThat(session.count("identity_refresh_token_family")).isZero();
            assertThat(session.count("identity_refresh_token")).isZero();

            session.run("select set_config('app.institution_id', '', true)");
            assertThat(session.count("identity_refresh_token_family")).isZero();
            assertThat(session.count("identity_refresh_token")).isZero();
        }
    }

    @Test
    void aFamilyOrTokenOfAnotherInstitutionIsRejectedByWithCheck() throws SQLException {
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();
        try (Session session = new Session("confia_admin_app")) {
            UUID accountB = session.seedAccount(institutionB);
            Seeded a = session.seedFamilyWithOpenToken(institutionA);

            session.contextOf(institutionA);
            session.assertRejected(INSUFFICIENT_PRIVILEGE, "row-level security", FAMILY_INSERT,
                    institutionB, UUID.randomUUID(), accountB, ZERO_FINGERPRINT, "192.0.2.1");

            session.contextOf(institutionB);
            session.assertRejected(INSUFFICIENT_PRIVILEGE, "row-level security", TOKEN_INSERT,
                    institutionA, UUID.randomUUID(), a.familyId(), someHash());
        }
    }

    @Test
    void aTokenCannotPointAtAFamilyOfAnotherInstitution() throws SQLException {
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();
        try (Session session = new Session("confia_admin_app")) {
            Seeded a = session.seedFamilyWithOpenToken(institutionA);
            session.seedAccount(institutionB);

            session.contextOf(institutionB);
            session.assertRejected(FOREIGN_KEY_VIOLATION, "identity_refresh_token_family_fk",
                    TOKEN_INSERT, institutionB, UUID.randomUUID(), a.familyId(), someHash());
        }
    }

    @Test
    void aFamilyCannotPointAtAnAccountOfAnotherInstitution() throws SQLException {
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();
        try (Session session = new Session("confia_admin_app")) {
            UUID accountA = session.seedAccount(institutionA);
            session.seedAccount(institutionB);

            session.contextOf(institutionB);
            session.assertRejected(FOREIGN_KEY_VIOLATION,
                    "identity_refresh_token_family_account_fk", FAMILY_INSERT, institutionB,
                    UUID.randomUUID(), accountA, ZERO_FINGERPRINT, "192.0.2.1");
        }
    }

    @Test
    void theCatalogueRejectsAHashThatIsNotASha256InHex() throws SQLException {
        UUID institution = UUID.randomUUID();
        String base64UrlToken = "q9Zb2X-kP3r_Tm7Wv1cH8eN4sLd0fGyJ5uAiO6pRtQE";
        assertThat(base64UrlToken).hasSize(43);
        try (Session session = new Session("confia_admin_app")) {
            UUID accountId = session.seedAccount(institution);
            UUID familyId = session.insertFamily(institution, accountId);

            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_hash_chk",
                    TOKEN_INSERT, institution, UUID.randomUUID(), familyId, base64UrlToken);
            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_hash_chk",
                    TOKEN_INSERT, institution, UUID.randomUUID(), familyId,
                    "A".repeat(64));
            assertThat(session.execute(TOKEN_INSERT, institution, UUID.randomUUID(), familyId,
                    someHash())).as("a well-formed hash for the same family is accepted")
                    .isEqualTo(1);
        }
    }

    @Test
    void theRevocationReasonCatalogueIsClosedAndRevokedAtNeedsItsReason() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session("confia_admin_app")) {
            UUID accountId = session.seedAccount(institution);

            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_family_reason_chk",
                    revokedFamilyInsert("'admin_revoke'"), institution, UUID.randomUUID(),
                    accountId, ZERO_FINGERPRINT);
            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_family_reason_chk",
                    revokedFamilyInsert("'logout'"), institution, UUID.randomUUID(), accountId,
                    ZERO_FINGERPRINT);
            assertThat(session.execute(revokedFamilyInsert("'reuse_detected'"), institution,
                    UUID.randomUUID(), accountId, ZERO_FINGERPRINT)).isEqualTo(1);
            assertThat(session.execute(revokedFamilyInsert("'password_change'"), institution,
                    UUID.randomUUID(), accountId, ZERO_FINGERPRINT)).isEqualTo(1);

            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_family_revoked_chk",
                    """
                    insert into identity_refresh_token_family
                        (institution_id, id, account_id, second_factor_verified,
                         user_agent_fingerprint, created_at, last_used_at, absolute_expires_at,
                         revoked_at)
                    values (?, ?, ?, false, ?, now(), now(), now() + interval '12 hours', now())
                    """, institution, UUID.randomUUID(), accountId, ZERO_FINGERPRINT);
            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_family_revoked_chk",
                    """
                    insert into identity_refresh_token_family
                        (institution_id, id, account_id, second_factor_verified,
                         user_agent_fingerprint, created_at, last_used_at, absolute_expires_at,
                         revoked_reason)
                    values (?, ?, ?, false, ?, now(), now(), now() + interval '12 hours',
                            'reuse_detected')
                    """, institution, UUID.randomUUID(), accountId, ZERO_FINGERPRINT);
        }
    }

    @Test
    void aFamilyNeedsAHexFingerprintAndAWindowThatOpensBeforeItCloses() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session("confia_admin_app")) {
            UUID accountId = session.seedAccount(institution);

            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_family_fp_chk",
                    FAMILY_INSERT, institution, UUID.randomUUID(), accountId,
                    "Mozilla/5.0", "192.0.2.1");
            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_family_window_chk",
                    """
                    insert into identity_refresh_token_family
                        (institution_id, id, account_id, second_factor_verified,
                         user_agent_fingerprint, created_at, last_used_at, absolute_expires_at)
                    values (?, ?, ?, false, ?, now(), now(), now())
                    """, institution, UUID.randomUUID(), accountId, ZERO_FINGERPRINT);
            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_family_window_chk",
                    """
                    insert into identity_refresh_token_family
                        (institution_id, id, account_id, second_factor_verified,
                         user_agent_fingerprint, created_at, last_used_at, absolute_expires_at)
                    values (?, ?, ?, false, ?, now(), now() - interval '1 minute',
                            now() + interval '12 hours')
                    """, institution, UUID.randomUUID(), accountId, ZERO_FINGERPRINT);
            assertThat(session.execute(FAMILY_INSERT, institution, UUID.randomUUID(), accountId,
                    ZERO_FINGERPRINT, "2001:db8::1"))
                    .as("an IPv6 address and a well-formed fingerprint are accepted")
                    .isEqualTo(1);
        }
    }

    @Test
    void aFamilyWithNoRecordedAddressIsAccepted() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session("confia_admin_app")) {
            UUID accountId = session.seedAccount(institution);

            assertThat(session.execute(FAMILY_INSERT, institution, UUID.randomUUID(), accountId,
                    ZERO_FINGERPRINT, null))
                    .as("ip_first_seen is nullable: a request may carry no usable address")
                    .isEqualTo(1);
        }
    }

    @Test
    void aTokenNeedsAWindowThatOpensBeforeItCloses() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session("confia_admin_app")) {
            UUID accountId = session.seedAccount(institution);
            UUID familyId = session.insertFamily(institution, accountId);

            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_window_chk", """
                    insert into identity_refresh_token
                        (institution_id, id, family_id, token_hash, issued_at, expires_at)
                    values (?, ?, ?, ?, now(), now())
                    """, institution, UUID.randomUUID(), familyId, someHash());
        }
    }

    @Test
    void aFamilyHoldsAtMostOneUnconsumedTokenAndATokenAtMostOnePredecessor()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session("confia_admin_app")) {
            Seeded seeded = session.seedFamilyWithOpenToken(institution);

            session.assertRejected(UNIQUE_VIOLATION, "identity_refresh_token_one_open_per_family",
                    TOKEN_INSERT, institution, UUID.randomUUID(), seeded.familyId(), someHash());

            UUID successor = UUID.randomUUID();
            assertThat(session.execute(CONSUME, ZERO_FINGERPRINT, successor, institution,
                    seeded.tokenId())).isEqualTo(1);
            assertThat(session.execute(TOKEN_INSERT, institution, successor, seeded.familyId(),
                    someHash()))
                    .as("consuming the open token and then inserting its successor is accepted")
                    .isEqualTo(1);

            session.assertRejected(UNIQUE_VIOLATION, "identity_refresh_token_one_predecessor",
                    """
                    insert into identity_refresh_token
                        (institution_id, id, family_id, token_hash, issued_at, expires_at,
                         consumed_at, consumed_user_agent_fingerprint, replaced_by)
                    values (?, ?, ?, ?, now(), now() + interval '8 hours', now(), ?, ?)
                    """, institution, UUID.randomUUID(), seeded.familyId(), someHash(),
                    ZERO_FINGERPRINT, successor);
        }
    }

    @Test
    void aSuccessorThatNeverGetsInsertedFailsTheDeferredForeignKeyAtCommit()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session("confia_admin_app")) {
            Seeded seeded = session.seedFamilyWithOpenToken(institution);
            session.execute(CONSUME, ZERO_FINGERPRINT, UUID.randomUUID(), institution,
                    seeded.tokenId());

            assertThatThrownBy(session::commit)
                    .as("replaced_by must name a real token by commit time, but not before")
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
    void consumptionIsAllOrNothingAndNeedsAShaFingerprint() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session("confia_admin_app")) {
            Seeded seeded = session.seedFamilyWithOpenToken(institution);

            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_consumed_chk", """
                    update identity_refresh_token set consumed_at = now()
                     where institution_id = ? and id = ?
                    """, institution, seeded.tokenId());
            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_consumed_chk", """
                    update identity_refresh_token
                       set consumed_at = now(), replaced_by = ?
                     where institution_id = ? and id = ?
                    """, UUID.randomUUID(), institution, seeded.tokenId());
            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_consumed_chk", """
                    update identity_refresh_token
                       set consumed_at = now(), consumed_user_agent_fingerprint = ?
                     where institution_id = ? and id = ?
                    """, ZERO_FINGERPRINT, institution, seeded.tokenId());
            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_consumed_fp_chk",
                    CONSUME, "Mozilla/5.0", UUID.randomUUID(), institution, seeded.tokenId());
            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_consumed_fp_chk",
                    CONSUME, "F".repeat(64), UUID.randomUUID(), institution, seeded.tokenId());

            assertThat(session.execute(CONSUME, ZERO_FINGERPRINT, UUID.randomUUID(), institution,
                    seeded.tokenId()))
                    .as("successor and fingerprint together are accepted").isEqualTo(1);
        }
    }

    @Test
    void anEncryptedSuccessorNeedsTheVersionPrefixAndAConsumedToken() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session("confia_admin_app")) {
            Seeded seeded = session.seedFamilyWithOpenToken(institution);
            // The ciphertext is written in the statement that consumes the token (the trigger
            // refuses a later write); RefreshTokenStateIntegrityIT covers that later write.
            String consumeWithCiphertext = """
                    update identity_refresh_token
                       set consumed_at = now(), consumed_user_agent_fingerprint = ?, replaced_by = ?,
                           successor_token_ciphertext = ?
                     where institution_id = ? and id = ?
                    """;

            session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_successor_chk", """
                    update identity_refresh_token set successor_token_ciphertext = ?
                     where institution_id = ? and id = ?
                    """, "v1:AAAA", institution, seeded.tokenId());
            for (String invalid : new String[] {"AAAA", "v2:AAAA", "v1:"}) {
                session.assertRejected(CHECK_VIOLATION, "identity_refresh_token_successor_chk",
                        consumeWithCiphertext, ZERO_FINGERPRINT, UUID.randomUUID(), invalid,
                        institution, seeded.tokenId());
            }
            assertThat(session.execute(consumeWithCiphertext, ZERO_FINGERPRINT, UUID.randomUUID(),
                    "v1:AAAA", institution, seeded.tokenId()))
                    .as("a v1: ciphertext written while consuming the token is accepted")
                    .isEqualTo(1);
        }
    }

    @ParameterizedTest(name = "DELETE on {0} is refused with 42501")
    @MethodSource("bothTables")
    void confiaAdminAppCanNeverDeleteFromEitherTable(String table) throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session("confia_admin_app")) {
            session.seedFamilyWithOpenToken(institution);
            assertThat(session.count(table))
                    .as("the row exists and is visible, so only the missing privilege can refuse")
                    .isEqualTo(1L);

            session.assertRejected(INSUFFICIENT_PRIVILEGE, "permission denied for table " + table,
                    "delete from " + table + " where institution_id = ?", institution);
        }
    }

    static Stream<String> bothTables() {
        return Stream.of("identity_refresh_token_family", "identity_refresh_token");
    }

    @ParameterizedTest(name = "UPDATE of {0}.{1} is refused with 42501")
    @MethodSource("immutableColumns")
    void confiaAdminAppCannotUpdateAnImmutableColumn(String table, String column, String value)
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session("confia_admin_app")) {
            session.seedFamilyWithOpenToken(institution);

            session.assertRejected(INSUFFICIENT_PRIVILEGE,
                    "permission denied for table " + table,
                    "update " + table + " set " + column + " = " + value
                            + " where institution_id = ?",
                    institution);
        }
    }

    static Stream<Arguments> immutableColumns() {
        String someUuid = "'" + UUID.randomUUID() + "'::uuid";
        String someHexHash = "'" + "1".repeat(64) + "'";
        return Stream.of(
                Arguments.of("identity_refresh_token", "token_hash", someHexHash),
                Arguments.of("identity_refresh_token", "expires_at", "now() + interval '1 day'"),
                Arguments.of("identity_refresh_token", "issued_at", "now() - interval '1 day'"),
                Arguments.of("identity_refresh_token", "family_id", someUuid),
                Arguments.of("identity_refresh_token", "institution_id", someUuid),
                Arguments.of("identity_refresh_token", "id", someUuid),
                Arguments.of("identity_refresh_token_family", "absolute_expires_at",
                        "now() + interval '1 year'"),
                Arguments.of("identity_refresh_token_family", "user_agent_fingerprint",
                        someHexHash),
                Arguments.of("identity_refresh_token_family", "ip_first_seen",
                        "cast('198.51.100.7' as inet)"),
                Arguments.of("identity_refresh_token_family", "institution_id", someUuid),
                Arguments.of("identity_refresh_token_family", "account_id", someUuid),
                Arguments.of("identity_refresh_token_family", "created_at",
                        "now() - interval '1 day'"),
                Arguments.of("identity_refresh_token_family", "second_factor_verified", "true"));
    }

    @Test
    void confiaAdminAppCanUpdateExactlyTheGrantedColumnsAndLockRowsWithForUpdate()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session("confia_admin_app")) {
            Seeded seeded = session.seedFamilyWithOpenToken(institution);

            assertThat(session.execute("""
                    update identity_refresh_token_family set last_used_at = now()
                     where institution_id = ? and id = ?
                    """, institution, seeded.familyId())).isEqualTo(1);
            assertThat(session.execute("""
                    update identity_refresh_token_family
                       set revoked_at = now(), revoked_reason = 'password_change'
                     where institution_id = ? and id = ?
                    """, institution, seeded.familyId())).isEqualTo(1);

            UUID successor = UUID.randomUUID();
            assertThat(session.execute("""
                    update identity_refresh_token
                       set consumed_at = now(), consumed_user_agent_fingerprint = ?,
                           replaced_by = ?, successor_token_ciphertext = 'v1:AAAA'
                     where institution_id = ? and id = ?
                    """, ZERO_FINGERPRINT, successor, institution, seeded.tokenId()))
                    .isEqualTo(1);
            session.execute(TOKEN_INSERT, institution, successor, seeded.familyId(), someHash());

            assertThat(session.lockedRows("identity_refresh_token_family", institution))
                    .as("SELECT ... FOR UPDATE on the family serializes a rotation").isEqualTo(1);
            assertThat(session.lockedRows("identity_refresh_token", institution))
                    .as("SELECT ... FOR UPDATE on the tokens is allowed too").isEqualTo(2);
        }
    }

    @Test
    void confiaReadonlyOnlyReadsTheRowsOfItsOwnInstitution() throws SQLException {
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();
        Seeded a;
        try (Session session = new Session("confia_admin_app")) {
            a = session.seedFamilyWithOpenToken(institutionA);
            session.seedFamilyWithOpenToken(institutionB);
            session.commit();
        }

        try (Session session = new Session("confia_readonly")) {
            session.contextOf(institutionB);
            assertThat(session.count("identity_refresh_token_family")).isEqualTo(1L);
            assertThat(session.count("identity_refresh_token")).isEqualTo(1L);
            assertThat(session.countWhere("identity_refresh_token", "id", a.tokenId())).isZero();

            session.assertRejected(INSUFFICIENT_PRIVILEGE, "permission denied for table",
                    TOKEN_INSERT, institutionB, UUID.randomUUID(), UUID.randomUUID(), someHash());
            session.assertRejected(INSUFFICIENT_PRIVILEGE, "permission denied for table",
                    "update identity_refresh_token_family set last_used_at = now()");
            session.assertRejected(INSUFFICIENT_PRIVILEGE, "permission denied for table",
                    "delete from identity_refresh_token");
        }
    }

    @ParameterizedTest(name = "confia_portal_app is refused on {0}")
    @MethodSource("bothTables")
    void confiaPortalAppHasNoPrivilegeAtAllOnEitherTable(String table) throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Session session = new Session("confia_portal_app")) {
            session.contextOf(institution);
            String expected = "permission denied for table " + table;
            session.assertRejected(INSUFFICIENT_PRIVILEGE, expected,
                    "select count(*) from " + table + " where institution_id = ?", institution);
            if (table.endsWith("_family")) {
                session.assertRejected(INSUFFICIENT_PRIVILEGE, expected, FAMILY_INSERT,
                        institution, UUID.randomUUID(), UUID.randomUUID(), ZERO_FINGERPRINT,
                        "192.0.2.1");
            } else {
                session.assertRejected(INSUFFICIENT_PRIVILEGE, expected, TOKEN_INSERT,
                        institution, UUID.randomUUID(), UUID.randomUUID(), someHash());
            }
            session.assertRejected(INSUFFICIENT_PRIVILEGE, expected,
                    "update " + table + " set institution_id = institution_id");
            session.assertRejected(INSUFFICIENT_PRIVILEGE, expected, "delete from " + table);
        }
    }

    @Test
    void theHelperItselfRejectsAStatementThatSucceeds() throws SQLException {
        try (Session session = new Session("confia_admin_app")) {
            assertThatCode(() -> session.assertRejected(INSUFFICIENT_PRIVILEGE, "anything",
                    "select 1")).as("control: a rejection assertion must fail on a statement "
                            + "that the database accepts")
                    .isInstanceOf(AssertionError.class);
        }
    }

    private static String revokedFamilyInsert(String reasonLiteral) {
        return """
                insert into identity_refresh_token_family
                    (institution_id, id, account_id, second_factor_verified,
                     user_agent_fingerprint, created_at, last_used_at, absolute_expires_at,
                     revoked_at, revoked_reason)
                values (?, ?, ?, false, ?, now(), now(), now() + interval '12 hours', now(),
                        %s)
                """.formatted(reasonLiteral);
    }

    /** 64 lowercase hex characters, the only thing {@code token_hash_chk} enforces. */
    private static String someHash() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    private record Seeded(UUID accountId, UUID familyId, UUID tokenId) {
    }

    /**
     * One real connection as one real role, in a manual transaction that rolls back on close unless
     * the test commits. Every expected failure runs behind a savepoint.
     */
    private static final class Session implements AutoCloseable {

        private final Connection connection;

        Session(String role) throws SQLException {
            this.connection = SharedPostgresContainer.connectionAs(role);
            this.connection.setAutoCommit(false);
        }

        void contextOf(UUID institutionId) throws SQLException {
            try (PreparedStatement statement = connection
                    .prepareStatement("select set_config('app.institution_id', ?, true)")) {
                statement.setString(1, institutionId.toString());
                statement.execute();
            }
        }

        UUID seedAccount(UUID institutionId) throws SQLException {
            contextOf(institutionId);
            UUID accountId = UUID.randomUUID();
            execute(ACCOUNT_INSERT, institutionId, accountId, "refresh." + accountId
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

        /** Runs a statement whose result, if any, is ignored (for {@code reset} and {@code set_config}). */
        void run(String sql) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.execute();
            }
        }

        long count(String table) throws SQLException {
            return scalar("select count(*) from " + table);
        }

        long countWhere(String table, String column, UUID value) throws SQLException {
            return scalar("select count(*) from " + table + " where " + column + " = ?", value);
        }

        long lockedRows(String table, UUID institutionId) throws SQLException {
            long rows = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                    "select institution_id from " + table + " where institution_id = ? for update")) {
                statement.setObject(1, institutionId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rows++;
                    }
                }
            }
            return rows;
        }

        private long scalar(String sql, Object... params) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                try (ResultSet resultSet = statement.executeQuery()) {
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
            if (failure != null) {
                connection.rollback(savepoint);
            }
            if (failure == null) {
                throw new AssertionError("the database must refuse: " + sql.strip());
            }
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
