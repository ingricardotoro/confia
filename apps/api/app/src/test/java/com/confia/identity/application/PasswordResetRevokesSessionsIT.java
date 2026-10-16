package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.application.ResetOutcome.Completed;
import com.confia.identity.application.ResetOutcome.PasswordRejected;
import com.confia.identity.application.ResetOutcome.SecondFactorMissing;
import com.confia.identity.application.ResetOutcome.SecondFactorRejected;
import com.confia.identity.application.ResetOutcome.TokenRejected;
import com.confia.identity.domain.AuthenticationResult.Authenticated;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.PlainTotpSecret;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqAccountSessionRevoker;
import com.confia.identity.infrastructure.JooqPasswordResetTokenRepository;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hard condition H2 (session-tokens-and-web-layer design.md decision 12), against a real
 * PostgreSQL: a completed password reset revokes every live refresh-token family of the account in
 * the same transaction that consumes the token and replaces the hash, and nothing else does.
 * Families are seeded with SQL because no issuance exists yet; every reset goes through the real use
 * case and the real adapters. Scenarios I24 and I122 to I125, plus "enrolling a second factor
 * revokes nothing".
 */
class PasswordResetRevokesSessionsIT extends PasswordResetIntegrationTest {

    private static final String ANA = "ana.martinez@colegio.edu.hn";
    private static final String CARLOS = "carlos.ramirez@colegio.edu.hn";
    private static final String SOFIA = "sofia.mejia@colegio.edu.hn";
    private static final String CURRENT = "Cafetal de Copán 2026";
    private static final String NEW = "Lempira y maíz 2026";
    private static final String ISSUED_AT = "2026-10-15T15:00:00Z";
    private static final String RESET_AT = "2026-10-15T15:05:00Z";
    private static final SecondFactorProof NONE = new SecondFactorProof.None();
    private static final String ACTION_REVOKED = "identity.session.revoked";
    private static final String ACTION_COMPLETED = "identity.password_reset.completed";
    private static final String MADE_UP_TOKEN = "q9Zb2X-kP3r_Tm7Wv1cH8eN4sLd0fGyJ5uAiO6pRtQE";
    private static final String ZERO_FINGERPRINT = "0".repeat(64);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Scenario I24: three families revoked, two of another account untouched, the count audited. */
    @Test
    void aCompletedResetRevokesEveryLiveFamilyOfTheAccountAndNoneOfAnother() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT, false);
        StaffAccountId carlos = seedStaffAccount(CARLOS, CURRENT, false);
        for (int i = 0; i < 3; i++) {
            seedFamily(ana, "2026-10-15T09:00:00Z", null, null, true);
        }
        for (int i = 0; i < 2; i++) {
            seedFamily(carlos, "2026-10-15T09:00:00Z", null, null, true);
        }
        PlainPasswordResetToken token = issueTokenAt(ana, ISSUED_AT);

        ResetPasswordDecision decision = resetAt(token.value(), NEW, NONE, RESET_AT);

        assertThat(decision.outcome()).isInstanceOf(Completed.class);
        List<Record> anaFamilies = families(ana);
        assertThat(anaFamilies).hasSize(3).allSatisfy(family -> {
            assertThat(family.get("revoked_reason", String.class)).isEqualTo("password_change");
            assertThat(family.get("revoked_at", OffsetDateTime.class).toInstant())
                    .isEqualTo(Instant.parse(RESET_AT));
        });
        assertThat(encryptedSuccessors(ana))
                .as("the revocation empties the encrypted successors of the families it revokes")
                .isZero();
        assertThat(families(carlos)).hasSize(2).allSatisfy(family -> {
            assertThat(family.get("revoked_at")).isNull();
            assertThat(family.get("revoked_reason")).isNull();
        });
        assertThat(encryptedSuccessors(carlos)).as("another account keeps its successors")
                .isEqualTo(2L);
        assertThat(loginAt(ANA, NEW, "2026-10-15T15:10:00Z")).isInstanceOf(Authenticated.class);

        List<AuditRowSnapshot> revoked = auditRows(ACTION_REVOKED);
        assertThat(revoked).singleElement().satisfies(row -> {
            assertThat(row.entityType()).isEqualTo("identity.staff_account");
            assertThat(row.entityId()).isEqualTo(ana.value().toString());
            assertThat(row.actorId()).isEqualTo(ana.value());
            assertThat(row.actorKind()).isEqualTo("staff");
            assertThat(row.outcome()).isEqualTo("success");
            JsonNode after = json(row.afterValue());
            assertThat(after.get("reason").asString()).isEqualTo("password_change");
            assertThat(after.get("families").asInt()).isEqualTo(3);
            assertThat(after.size()).as("nothing but the reason and the count").isEqualTo(2);
        });
    }

    /** Scenario I122: a failure after consuming, rehashing and revoking leaves nothing behind. */
    @Test
    void aTransactionThatRollsBackAfterRevokingLeavesEveryFamilyLive() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT, false);
        for (int i = 0; i < 3; i++) {
            seedFamily(ana, "2026-10-15T09:00:00Z", null, null, true);
        }
        PlainPasswordResetToken token = issueTokenAt(ana, ISSUED_AT);
        List<Integer> revocations = new ArrayList<>();
        AccountSessionRevoker spy = spyOn(new JooqAccountSessionRevoker(dsl), revocations);
        JooqAuditLogWriter realAudit = new JooqAuditLogWriter(dsl);
        AuditLogWriter failingOnCompletion = entry -> {
            if (entry.action().equals(ACTION_COMPLETED)) {
                throw new IllegalStateException("audit unavailable after the revocation");
            }
            realAudit.append(entry);
        };

        assertThatThrownBy(() -> resetUseCase(RESET_AT, failingOnCompletion,
                new JooqPasswordResetTokenRepository(dsl), institutionId, spy)
                .execute(context(), new ResetPasswordCommand(token.value(), NEW, NONE)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(revocations)
                .as("the revoker did revoke three families before the rollback")
                .containsExactly(3);
        assertThat(families(ana)).hasSize(3)
                .allSatisfy(family -> assertThat(family.get("revoked_at")).isNull());
        assertThat(encryptedSuccessors(ana)).isEqualTo(3L);
        assertThat(isOpen(token)).isTrue();
        assertThat(loginAt(ANA, CURRENT, "2026-10-15T15:10:00Z")).isInstanceOf(Authenticated.class);
        assertThat(auditRows(ACTION_REVOKED)).isEmpty();
    }

    /** The revocation entry is part of the unit too: if it cannot be written, nothing happens. */
    @Test
    void aFailingRevocationEntryRollsBackTheWholeReset() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT, false);
        seedFamily(ana, "2026-10-15T09:00:00Z", null, null, true);
        PlainPasswordResetToken token = issueTokenAt(ana, ISSUED_AT);
        JooqAuditLogWriter realAudit = new JooqAuditLogWriter(dsl);
        AuditLogWriter failingOnRevocation = entry -> {
            if (entry.action().equals(ACTION_REVOKED)) {
                throw new IllegalStateException("audit unavailable for the revocation entry");
            }
            realAudit.append(entry);
        };

        assertThatThrownBy(() -> resetWith(token.value(), NEW, NONE, RESET_AT, failingOnRevocation,
                new JooqPasswordResetTokenRepository(dsl)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(families(ana)).singleElement()
                .satisfies(family -> assertThat(family.get("revoked_at")).isNull());
        assertThat(isOpen(token)).isTrue();
        assertThat(loginAt(ANA, CURRENT, "2026-10-15T15:10:00Z")).isInstanceOf(Authenticated.class);
        assertThat(auditRows(ACTION_COMPLETED)).isEmpty();
    }

    /** Scenario I123: a revoker that throws means no reset: same password, token still alive. */
    @Test
    void aRevokerThatFailsLeavesThePasswordAndTheTokenUntouched() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT, false);
        seedFamily(ana, "2026-10-15T09:00:00Z", null, null, true);
        PlainPasswordResetToken token = issueTokenAt(ana, ISSUED_AT);
        AccountSessionRevoker failing = (institution, account, reason, now) -> {
            throw new IllegalStateException("revocation unavailable");
        };

        assertThatThrownBy(() -> resetUseCase(RESET_AT, new JooqAuditLogWriter(dsl),
                new JooqPasswordResetTokenRepository(dsl), institutionId, failing)
                .execute(context(), new ResetPasswordCommand(token.value(), NEW, NONE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("revocation unavailable");

        assertThat(isOpen(token)).isTrue();
        assertThat(loginAt(ANA, CURRENT, "2026-10-15T15:10:00Z")).isInstanceOf(Authenticated.class);
        assertThat(families(ana)).singleElement()
                .satisfies(family -> assertThat(family.get("revoked_at")).isNull());
    }

    /** Scenario I124: the four rejections leave three live families exactly as they were. */
    @Test
    void noRejectionRevokesAnyFamily() {
        StaffAccountId sofia = seedStaffAccount(SOFIA, CURRENT, true);
        PlainTotpSecret secret = enrollTotp(sofia);
        for (int i = 0; i < 3; i++) {
            seedFamily(sofia, "2026-10-15T09:00:00Z", null, null, true);
        }
        PlainPasswordResetToken token = issueTokenAt(sofia, ISSUED_AT);

        ResetPasswordDecision unknownToken = resetAt(MADE_UP_TOKEN, NEW, NONE, RESET_AT);
        ResetPasswordDecision shortPassword = resetAt(token.value(), "a".repeat(11),
                new SecondFactorProof.Totp(validCodeAt(secret, RESET_AT)), RESET_AT);
        ResetPasswordDecision noSecondFactor = resetAt(token.value(), NEW, NONE, RESET_AT);
        ResetPasswordDecision wrongSecondFactor = resetAt(token.value(), NEW,
                new SecondFactorProof.Totp(wrongCodeAt(secret, RESET_AT)), RESET_AT);

        assertThat(unknownToken.outcome()).isInstanceOf(TokenRejected.class);
        assertThat(shortPassword.outcome()).isInstanceOf(PasswordRejected.class);
        assertThat(noSecondFactor.outcome()).isInstanceOf(SecondFactorMissing.class);
        assertThat(wrongSecondFactor.outcome()).isInstanceOf(SecondFactorRejected.class);
        assertThat(families(sofia)).hasSize(3)
                .allSatisfy(family -> assertThat(family.get("revoked_at")).isNull());
        assertThat(encryptedSuccessors(sofia)).isEqualTo(3L);
        assertThat(auditRows(ACTION_REVOKED)).isEmpty();
        assertThat(isOpen(token)).as("the token survived every rejection").isTrue();
    }

    /** Scenario I125, first half: no families at all, and the entry still says zero. */
    @Test
    void anAccountWithoutFamiliesCompletesAndAuditsZeroRevocations() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT, false);
        PlainPasswordResetToken token = issueTokenAt(ana, ISSUED_AT);

        ResetPasswordDecision decision = resetAt(token.value(), NEW, NONE, RESET_AT);

        assertThat(decision.outcome()).isInstanceOf(Completed.class);
        assertThat(families(ana)).isEmpty();
        assertThat(auditRows(ACTION_REVOKED)).singleElement().satisfies(row -> {
            JsonNode after = json(row.afterValue());
            assertThat(after.get("reason").asString()).isEqualTo("password_change");
            assertThat(after.get("families").asInt()).isZero();
        });
    }

    /** Scenario I125, second half: an already revoked family keeps its instant and its reason. */
    @Test
    void anAlreadyRevokedFamilyKeepsItsReasonAndOnlyTheLiveOneIsRevoked() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT, false);
        UUID reused = seedFamily(ana, "2026-10-15T07:00:00Z", "2026-10-15T08:00:00Z",
                "reuse_detected", false);
        UUID live = seedFamily(ana, "2026-10-15T09:00:00Z", null, null, true);
        PlainPasswordResetToken token = issueTokenAt(ana, ISSUED_AT);

        ResetPasswordDecision decision = resetAt(token.value(), NEW, NONE, RESET_AT);

        assertThat(decision.outcome()).isInstanceOf(Completed.class);
        Record keptRevocation = family(reused);
        assertThat(keptRevocation.get("revoked_reason", String.class)).isEqualTo("reuse_detected");
        assertThat(keptRevocation.get("revoked_at", OffsetDateTime.class).toInstant())
                .isEqualTo(Instant.parse("2026-10-15T08:00:00Z"));
        Record nowRevoked = family(live);
        assertThat(nowRevoked.get("revoked_reason", String.class)).isEqualTo("password_change");
        assertThat(nowRevoked.get("revoked_at", OffsetDateTime.class).toInstant())
                .isEqualTo(Instant.parse(RESET_AT));
        assertThat(auditRows(ACTION_REVOKED)).singleElement().satisfies(row ->
                assertThat(json(row.afterValue()).get("families").asInt())
                        .as("only the live family counts").isEqualTo(1));
    }

    /** Enrolling a second factor is not a password change: it revokes nothing. */
    @Test
    void enrollingASecondFactorRevokesNothing() {
        StaffAccountId sofia = seedStaffAccount(SOFIA, CURRENT, true);
        for (int i = 0; i < 2; i++) {
            seedFamily(sofia, "2026-10-15T09:00:00Z", null, null, true);
        }

        EnrollTotpSecondFactorResult enrollment = enroll(sofia);

        assertThat(enrollment.secret()).isNotNull();
        assertThat(families(sofia)).hasSize(2)
                .allSatisfy(family -> assertThat(family.get("revoked_at")).isNull());
        assertThat(encryptedSuccessors(sofia)).isEqualTo(2L);
        assertThat(auditRows(ACTION_REVOKED)).isEmpty();
    }

    /** The real adapter, recording how many families each call revoked. */
    private static AccountSessionRevoker spyOn(AccountSessionRevoker delegate,
            List<Integer> revocations) {
        return (institution, account, reason, now) -> {
            int revoked = delegate.revokeAllLive(institution, account, reason, now);
            revocations.add(revoked);
            return revoked;
        };
    }

    /**
     * A family with a consumed token carrying an encrypted successor and its open successor (when
     * {@code withEncryptedSuccessor}), or with a single open token. The deferred successor key lets
     * the predecessor name its successor before the successor row exists.
     */
    private UUID seedFamily(StaffAccountId account, String createdAt, String revokedAt,
            String revokedReason, boolean withEncryptedSuccessor) {
        UUID familyId = UUID.randomUUID();
        UUID predecessor = UUID.randomUUID();
        UUID successor = UUID.randomUUID();
        transactionRunner().execute(context(), () -> {
            dsl.execute("""
                    insert into identity_refresh_token_family
                        (institution_id, id, account_id, second_factor_verified,
                         user_agent_fingerprint, created_at, last_used_at, absolute_expires_at,
                         revoked_at, revoked_reason)
                    values (?, ?, ?, false, ?, cast(? as timestamptz), cast(? as timestamptz),
                            cast(? as timestamptz) + interval '12 hours',
                            cast(? as timestamptz), ?)
                    """, institutionId.value(), familyId, account.value(), ZERO_FINGERPRINT,
                    createdAt, createdAt, createdAt, revokedAt, revokedReason);
            insertToken(predecessor, familyId, createdAt);
            if (withEncryptedSuccessor) {
                dsl.execute("""
                        update identity_refresh_token
                           set consumed_at = cast(? as timestamptz),
                               consumed_user_agent_fingerprint = ?, replaced_by = ?,
                               successor_token_ciphertext = 'v1:AAAA'
                         where institution_id = ? and id = ?
                        """, createdAt, ZERO_FINGERPRINT, successor, institutionId.value(),
                        predecessor);
                insertToken(successor, familyId, createdAt);
            }
            return null;
        });
        return familyId;
    }

    private void insertToken(UUID tokenId, UUID familyId, String issuedAt) {
        dsl.execute("""
                insert into identity_refresh_token
                    (institution_id, id, family_id, token_hash, issued_at, expires_at)
                values (?, ?, ?, ?, cast(? as timestamptz),
                        cast(? as timestamptz) + interval '8 hours')
                """, institutionId.value(), tokenId, familyId,
                (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""), issuedAt,
                issuedAt);
    }

    private List<Record> families(StaffAccountId account) {
        return transactionRunner().execute(context(), () -> new ArrayList<Record>(dsl.fetch("""
                select id, revoked_at, revoked_reason from identity_refresh_token_family
                 where institution_id = ? and account_id = ? order by id
                """, institutionId.value(), account.value())));
    }

    private Record family(UUID familyId) {
        return transactionRunner().execute(context(), () -> dsl.fetchSingle("""
                select id, revoked_at, revoked_reason from identity_refresh_token_family
                 where institution_id = ? and id = ?
                """, institutionId.value(), familyId));
    }

    private long encryptedSuccessors(StaffAccountId account) {
        return transactionRunner().execute(context(), () -> dsl.fetchSingle("""
                select count(*) from identity_refresh_token t
                  join identity_refresh_token_family f
                    on f.institution_id = t.institution_id and f.id = t.family_id
                 where t.institution_id = ? and f.account_id = ?
                   and t.successor_token_ciphertext is not null
                """, institutionId.value(), account.value()).get(0, Long.class));
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
