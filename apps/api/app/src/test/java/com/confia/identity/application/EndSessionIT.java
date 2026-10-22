package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.application.RefreshOutcome.Rotated;
import com.confia.identity.domain.PlainRefreshToken;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqAccountSessionRevoker;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The two ways a session is ended on purpose (session-endpoints-cookie-and-csrf design.md decision
 * 15; scenarios I195 to I197 and I200), against a real PostgreSQL through the real adapters. There
 * is no controller yet: these are the use cases alone. {@link EndCurrentSession} closes the family
 * of a presented refresh token, also when the token was already consumed, and does nothing, and
 * writes nothing, for a token that is unknown, malformed, of another institution or whose family is
 * already revoked or has ended. {@link EndAllSessions} closes every live family of the account and
 * none of another account or institution.
 */
class EndSessionIT extends RevocationIntegrationTest {

    private static final String CARLOS = "carlos.ramirez@colegio.edu.hn";
    private static final String LOGOUT_AT = "2026-10-15T15:20:00Z";
    private static final String MADE_UP_TOKEN = "q9Zb2X-kP3r_Tm7Wv1cH8eN4sLd0fGyJ5uAiO6pRtQE";

    // -- EndCurrentSession ------------------------------------------------------------------------

    @Test
    void aLiveSessionIsRevokedWithLogoutItsSuccessorsAreEmptiedAndTheClosingIsAudited() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession first = issue("2026-10-15T15:00:00Z", ana);
        Rotated rotated = (Rotated) rotate("2026-10-15T15:05:00Z",
                first.refreshToken().value(), AGENT_ONE);
        assertThat(encryptedSuccessorsIn(institutionId, ana))
                .as("sanity: the rotation left an encrypted successor to empty").isEqualTo(1L);

        endCurrentSession(LOGOUT_AT).execute(rotated.session().refreshToken().value(),
                UUID.randomUUID().toString());

        Record family = familyIn(institutionId, first.sessionId());
        assertThat(family.get("revoked_reason", String.class)).isEqualTo("logout");
        assertThat(instantOf(family, "revoked_at")).isEqualTo(Instant.parse(LOGOUT_AT));
        assertThat(encryptedSuccessorsIn(institutionId, ana)).isZero();
        assertThat(liveTokensIn(institutionId, ana))
                .as("a revoked family has no token that can be presented").isZero();
        assertThat(auditRowsIn(institutionId, ACTION_REVOKED)).singleElement().satisfies(row -> {
            assertThat(row.entityType()).as("the entity is the account, as in the reset")
                    .isEqualTo("identity.staff_account");
            assertThat(row.entityId()).isEqualTo(ana.value().toString());
            assertThat(row.actorId()).isEqualTo(ana.value());
            assertThat(row.actorKind()).isEqualTo("staff");
            assertThat(row.outcome()).isEqualTo("success");
            assertLogoutDocument(row, 1);
        });
    }

    /** Scenario I195: the token of a rotation that already happened still locates the family. */
    @Test
    void aConsumedRefreshTokenAlsoClosesTheFamily() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession first = issue("2026-10-15T15:00:00Z", ana);
        Rotated second = (Rotated) rotate("2026-10-15T15:05:00Z", first.refreshToken().value(),
                AGENT_ONE);
        rotate("2026-10-15T15:20:00Z", second.session().refreshToken().value(), AGENT_ONE);

        endCurrentSession("2026-10-15T15:25:00Z").execute(first.refreshToken().value(),
                UUID.randomUUID().toString());

        Record family = familyIn(institutionId, first.sessionId());
        assertThat(family.get("revoked_reason", String.class)).isEqualTo("logout");
        assertThat(instantOf(family, "revoked_at"))
                .isEqualTo(Instant.parse("2026-10-15T15:25:00Z"));
        assertThat(auditRowsIn(institutionId, ACTION_REVOKED)).singleElement()
                .satisfies(row -> assertLogoutDocument(row, 1));
    }

    /** Scenario I197: only the family of the presented token. */
    @Test
    void onlyTheFamilyOfThePresentedTokenIsRevoked() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession closed = issue("2026-10-15T15:00:00Z", ana);
        IssuedSession kept = issue("2026-10-15T15:01:00Z", ana);

        endCurrentSession(LOGOUT_AT).execute(closed.refreshToken().value(),
                UUID.randomUUID().toString());

        assertThat(familyIn(institutionId, closed.sessionId()).get("revoked_at")).isNotNull();
        Record other = familyIn(institutionId, kept.sessionId());
        assertThat(other.get("revoked_at")).isNull();
        assertThat(other.get("revoked_reason")).isNull();
        assertThat(liveTokensIn(institutionId, ana))
                .as("the other family keeps its open token").isEqualTo(1L);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-a-token", "q9Zb2X-kP3r_Tm7Wv1cH8eN4sLd0fGyJ5uAiO6pRtQ",
            "q9Zb2X-kP3r_Tm7Wv1cH8eN4sLd0fGyJ5uAiO6pRtQEx", "q9Zb2X+kP3r/Tm7Wv1cH8eN4sLd0fGyJ5uAiO6pRtQE"})
    void aMissingOrMalformedTextDoesNothingAndWritesNothing(String presented) {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        issue("2026-10-15T15:00:00Z", ana);
        String before = storedSessions();
        int auditBefore = auditRows().size();

        endCurrentSession(LOGOUT_AT).execute(presented, UUID.randomUUID().toString());

        assertThat(storedSessions()).isEqualTo(before);
        assertThat(auditRows()).hasSize(auditBefore);
    }

    /** Scenario I196, first half: a well-formed token that no family owns. */
    @Test
    void anUnknownTokenDoesNothingAndWritesNothing() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        issue("2026-10-15T15:00:00Z", ana);
        String before = storedSessions();
        int auditBefore = auditRows().size();

        endCurrentSession(LOGOUT_AT).execute(MADE_UP_TOKEN, UUID.randomUUID().toString());

        assertThat(storedSessions()).isEqualTo(before);
        assertThat(auditRows()).hasSize(auditBefore);
    }

    /** Scenario I196, second half: a revoked family keeps its reason and its instant. */
    @Test
    void anAlreadyRevokedFamilyKeepsItsReasonAndItsInstantAndWritesNoEntry() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID reused = seedFamily(ana, false, "2026-10-15T13:00:00Z", "2026-10-15T13:30:00Z",
                "2026-10-16T01:00:00Z", "2026-10-15T13:30:00Z", "reuse_detected");
        PlainRefreshToken token = seedOpenToken(reused, "2026-10-15T13:00:00Z",
                "2026-10-15T21:00:00Z");
        String before = storedSessions();

        endCurrentSession(LOGOUT_AT).execute(token.value(), UUID.randomUUID().toString());

        Record family = familyIn(institutionId, reused);
        assertThat(family.get("revoked_reason", String.class)).isEqualTo("reuse_detected");
        assertThat(instantOf(family, "revoked_at"))
                .isEqualTo(Instant.parse("2026-10-15T13:30:00Z"));
        assertThat(storedSessions()).isEqualTo(before);
        assertThat(auditRowsIn(institutionId, ACTION_REVOKED)).isEmpty();
    }

    @Test
    void aFamilyThatHasEndedByTimeIsNotRevokedAndWritesNoEntry() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID expired = seedFamily(ana, false, "2026-10-15T01:00:00Z", "2026-10-15T14:50:00Z",
                "2026-10-15T13:00:00Z", null, null);
        PlainRefreshToken afterEnd = seedOpenToken(expired, "2026-10-15T01:00:00Z",
                "2026-10-15T13:00:00Z");
        UUID idle = seedFamily(ana, false, "2026-10-15T14:00:00Z", "2026-10-15T14:40:00Z",
                "2026-10-16T02:00:00Z", null, null);
        PlainRefreshToken idleToken = seedOpenToken(idle, "2026-10-15T14:00:00Z",
                "2026-10-15T22:00:00Z");

        endCurrentSession(LOGOUT_AT).execute(afterEnd.value(), UUID.randomUUID().toString());
        endCurrentSession(LOGOUT_AT).execute(idleToken.value(), UUID.randomUUID().toString());

        assertThat(familyIn(institutionId, expired).get("revoked_at")).isNull();
        assertThat(familyIn(institutionId, idle).get("revoked_at")).isNull();
        assertThat(auditRowsIn(institutionId, ACTION_REVOKED)).isEmpty();
    }

    @Test
    void aFamilyAtTheExactEndOfItsLifeAndOneAtTheExactIdleBorderAreNotRevoked() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID atEnd = seedFamily(ana, false, "2026-10-15T03:20:00Z", "2026-10-15T15:10:00Z",
                LOGOUT_AT, null, null);
        PlainRefreshToken endToken = seedOpenToken(atEnd, "2026-10-15T03:20:00Z",
                LOGOUT_AT);
        UUID atIdle = seedFamily(ana, false, "2026-10-15T14:00:00Z", "2026-10-15T14:50:00Z",
                "2026-10-16T02:00:00Z", null, null);
        PlainRefreshToken idleToken = seedOpenToken(atIdle, "2026-10-15T14:00:00Z",
                "2026-10-15T22:00:00Z");

        endCurrentSession(LOGOUT_AT).execute(endToken.value(), UUID.randomUUID().toString());
        endCurrentSession(LOGOUT_AT).execute(idleToken.value(), UUID.randomUUID().toString());

        assertThat(familyIn(institutionId, atEnd).get("revoked_at")).isNull();
        assertThat(familyIn(institutionId, atIdle).get("revoked_at")).isNull();
        assertThat(auditRowsIn(institutionId, ACTION_REVOKED)).isEmpty();
    }

    /** Row-level security keeps another institution's family out of the lookup. */
    @Test
    void aTokenOfAnotherInstitutionHasNoEffectOnItsFamilyAndWritesNoEntry() {
        InstitutionId other = new InstitutionId(UUID.randomUUID());
        StaffAccountId stranger = seedStaffAccountIn(other, ANA);
        IssuedSession foreign = issueIn(other, "2026-10-15T15:00:00Z", stranger);
        String before = storedSessionsIn(other);

        endCurrentSession(LOGOUT_AT).execute(foreign.refreshToken().value(),
                UUID.randomUUID().toString());

        assertThat(familyIn(other, foreign.sessionId()).get("revoked_at")).isNull();
        assertThat(storedSessionsIn(other)).isEqualTo(before);
        assertThat(auditRowsIn(other, ACTION_REVOKED)).isEmpty();
        assertThat(auditRowsIn(institutionId, ACTION_REVOKED)).isEmpty();
    }

    @Test
    void aClosingThatCannotBeAuditedLeavesTheFamilyLive() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession session = issue("2026-10-15T15:00:00Z", ana);
        JooqAuditLogWriter real = new JooqAuditLogWriter(dsl);
        AuditLogWriter failing = entry -> {
            if (entry.action().equals(ACTION_REVOKED)) {
                throw new IllegalStateException("audit unavailable for the closing entry");
            }
            real.append(entry);
        };

        assertThatThrownBy(() -> endCurrentSession(institutionId, LOGOUT_AT, failing)
                .execute(session.refreshToken().value(), UUID.randomUUID().toString()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(familyIn(institutionId, session.sessionId()).get("revoked_at")).isNull();
        assertThat(liveTokensIn(institutionId, ana)).isEqualTo(1L);
    }

    @Test
    void theRequestIdentifierGoesToTheEntryAndABlankOneGetsAFreshIdentifier() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession withId = issue("2026-10-15T15:00:00Z", ana);
        IssuedSession blank = issue("2026-10-15T15:01:00Z", ana);
        UUID requestId = UUID.randomUUID();

        endCurrentSession(LOGOUT_AT).execute(withId.refreshToken().value(), requestId.toString());
        endCurrentSession(LOGOUT_AT).execute(blank.refreshToken().value(), "");

        List<AuditRowSnapshot> rows = auditRowsIn(institutionId, ACTION_REVOKED);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(AuditRowSnapshot::requestId).contains(requestId)
                .doesNotContainNull();
        assertThat(rows.stream().map(AuditRowSnapshot::requestId).distinct().count()).isEqualTo(2L);
    }

    // -- EndAllSessions ---------------------------------------------------------------------------

    /** Scenario I200: every live family of the account, none of another account. */
    @Test
    void everyLiveFamilyOfTheAccountIsRevokedAndNoneOfAnotherOrAlreadyRevoked() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        StaffAccountId carlos = seedStaffAccount(CARLOS, CURRENT_PASSWORD, false);
        IssuedSession current = issue("2026-10-15T15:00:00Z", ana);
        issue("2026-10-15T15:01:00Z", ana);
        rotate("2026-10-15T15:05:00Z", issue("2026-10-15T15:02:00Z", ana).refreshToken().value(),
                AGENT_ONE);
        UUID reused = seedFamily(ana, false, "2026-10-15T13:00:00Z", "2026-10-15T13:30:00Z",
                "2026-10-16T01:00:00Z", "2026-10-15T13:30:00Z", "reuse_detected");
        issue("2026-10-15T15:03:00Z", carlos);
        issue("2026-10-15T15:04:00Z", carlos);
        assertThat(encryptedSuccessorsIn(institutionId, ana))
                .as("sanity: the rotated family holds an encrypted successor to empty").isEqualTo(1L);

        endAllSessions(LOGOUT_AT).execute(actorOf(institutionId, ana, current.sessionId()),
                UUID.randomUUID().toString());

        List<Record> anaFamilies = familiesIn(institutionId, ana);
        assertThat(anaFamilies).hasSize(4);
        assertThat(anaFamilies.stream().filter(f -> f.get("revoked_reason", String.class)
                .equals("logout"))).hasSize(3).allSatisfy(family ->
                assertThat(instantOf(family, "revoked_at")).isEqualTo(Instant.parse(LOGOUT_AT)));
        Record kept = familyIn(institutionId, reused);
        assertThat(kept.get("revoked_reason", String.class)).isEqualTo("reuse_detected");
        assertThat(instantOf(kept, "revoked_at")).isEqualTo(Instant.parse("2026-10-15T13:30:00Z"));
        assertThat(liveTokensIn(institutionId, ana)).isZero();
        assertThat(encryptedSuccessorsIn(institutionId, ana)).isZero();
        assertThat(familiesIn(institutionId, carlos)).hasSize(2).allSatisfy(family ->
                assertThat(family.get("revoked_at")).isNull());
        assertThat(liveTokensIn(institutionId, carlos)).isEqualTo(2L);
        assertThat(auditRowsIn(institutionId, ACTION_REVOKED)).singleElement().satisfies(row -> {
            assertThat(row.entityType()).isEqualTo("identity.staff_account");
            assertThat(row.entityId()).isEqualTo(ana.value().toString());
            assertThat(row.actorId()).isEqualTo(ana.value());
            assertThat(row.actorKind()).isEqualTo("staff");
            assertThat(row.outcome()).isEqualTo("success");
            assertLogoutDocument(row, 3);
        });
    }

    @Test
    void anAccountWithNoLiveFamilyStillWritesTheEntryWithZero() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);

        endAllSessions(LOGOUT_AT).execute(actorOf(institutionId, ana, UUID.randomUUID()),
                UUID.randomUUID().toString());

        assertThat(auditRowsIn(institutionId, ACTION_REVOKED)).singleElement().satisfies(row ->
                assertLogoutDocument(row, 0));
    }

    @Test
    void theAccountIsLockedBeforeTheFamiliesAndTheRevocationCarriesTheLogoutReason() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        issue("2026-10-15T15:00:00Z", ana);
        List<String> calls = new ArrayList<>();
        JooqStaffAccountRepository realAccounts = new JooqStaffAccountRepository(dsl);
        StaffAccountRepository recordingAccounts = new StaffAccountRepository() {
            @Override
            public Optional<com.confia.identity.domain.StaffAccount> findBy(
                    InstitutionId institution, com.confia.identity.domain.LoginIdentifier id) {
                return realAccounts.findBy(institution, id);
            }

            @Override
            public Optional<com.confia.identity.domain.StaffAccount> lockById(
                    InstitutionId institution, StaffAccountId account) {
                calls.add("lock-account");
                return realAccounts.lockById(institution, account);
            }

            @Override
            public boolean replacePasswordHash(InstitutionId institution, StaffAccountId account,
                    com.confia.identity.domain.StoredPasswordHash hash) {
                return realAccounts.replacePasswordHash(institution, account, hash);
            }
        };
        JooqAccountSessionRevoker realRevoker = new JooqAccountSessionRevoker(dsl);
        AccountSessionRevoker recordingRevoker = (institution, account, reason, now) -> {
            calls.add("revoke-" + reason.code());
            return realRevoker.revokeAllLive(institution, account, reason, now);
        };

        endAllSessions(LOGOUT_AT, recordingAccounts, recordingRevoker,
                new JooqAuditLogWriter(dsl)).execute(actorOf(institutionId, ana, UUID.randomUUID()),
                UUID.randomUUID().toString());

        assertThat(calls).containsExactly("lock-account", "revoke-logout");
    }

    @Test
    void aClosingThatCannotBeAuditedLeavesEveryFamilyLive() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession first = issue("2026-10-15T15:00:00Z", ana);
        issue("2026-10-15T15:01:00Z", ana);
        JooqAuditLogWriter real = new JooqAuditLogWriter(dsl);
        AuditLogWriter failing = entry -> {
            if (entry.action().equals(ACTION_REVOKED)) {
                throw new IllegalStateException("audit unavailable for the closing entry");
            }
            real.append(entry);
        };

        assertThatThrownBy(() -> endAllSessions(LOGOUT_AT, new JooqStaffAccountRepository(dsl),
                new JooqAccountSessionRevoker(dsl), failing)
                .execute(actorOf(institutionId, ana, first.sessionId()),
                        UUID.randomUUID().toString()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(familiesIn(institutionId, ana)).hasSize(2).allSatisfy(family ->
                assertThat(family.get("revoked_at")).isNull());
        assertThat(liveTokensIn(institutionId, ana)).isEqualTo(2L);
    }

    @Test
    void anActorWhoseAccountDoesNotExistIsAFailureNotASilentZero() {
        StaffAccountId ghost = new StaffAccountId(UUID.randomUUID());

        assertThatThrownBy(() -> endAllSessions(LOGOUT_AT).execute(
                actorOf(institutionId, ghost, UUID.randomUUID()), UUID.randomUUID().toString()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(auditRowsIn(institutionId, ACTION_REVOKED)).isEmpty();
    }

    private String storedSessionsIn(InstitutionId institution) {
        return transactionRunner().execute(contextOf(institution), () -> dsl.fetch("""
                select row_to_json(f)::text from identity_refresh_token_family f
                union all
                select row_to_json(t)::text from identity_refresh_token t
                order by 1
                """).formatCSV());
    }
}
