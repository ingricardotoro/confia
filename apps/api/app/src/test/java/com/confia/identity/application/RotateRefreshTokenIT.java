package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.application.RefreshOutcome.Rejected;
import com.confia.identity.application.RefreshOutcome.Rotated;
import com.confia.identity.application.RefreshOutcome.RotationKind;
import com.confia.identity.domain.PlainRefreshToken;
import com.confia.identity.domain.RefreshRejection;
import com.confia.identity.domain.RefreshTokenHash;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.AuthenticationMethod;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rotating a refresh token (session-tokens-and-web-layer design.md decision 10; scenarios I91 to
 * I94, I109 to I114, I121, I142 and I145, and the absences of row retention and of binding to the
 * agent and the address), against a real PostgreSQL and the real adapters. Every rejection is a
 * returned value, and the entry it leaves is committed. Families whose window a real issuance could
 * not produce are seeded with SQL; everything stored is read back with raw SQL.
 *
 * <p>The reuse and the grace branches are not exercised for their effects here: they return a
 * rejection with no effect until tasks 4.2 and 4.3 give them one. The address of the client is not an
 * input of the rotation, so "another address" cannot change its outcome, and the test that changes the
 * agent is the whole of that absence.
 */
class RotateRefreshTokenIT extends RotationIntegrationTest {

    private static final String EIGHT = "2026-10-12T08:00:00Z";
    private static final String NINE = "2026-10-12T09:00:00Z";
    private static final String TWENTY = "2026-10-12T20:00:00Z";
    private static final String ACTION_REFRESHED = "identity.session.refreshed";
    private static final String ACTION_REJECTED = "identity.session.refresh_rejected";
    private static final String ACTION_REUSE_DETECTED = "identity.session.reuse_detected";
    private static final String SIXTEEN = "2026-10-12T16:00:00Z";
    private static final String MADE_UP = "q9Zb2X-kP3r_Tm7Wv1cH8eN4sLd0fGyJ5uAiO6pRtQE";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Scenario I91, with the stored result: one consumed token, one open successor, one entry. */
    @Test
    void aFreshRotationConsumesTheTokenAndLeavesExactlyOneSuccessor() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession first = issueSession(NINE).execute(context(),
                commandFrom(ana, false, AGENT_ONE, "203.0.113.9"));

        RefreshOutcome outcome = rotate("2026-10-12T09:15:00Z", first.refreshToken().value(),
                AGENT_ONE);

        assertThat(outcome).isInstanceOf(Rotated.class);
        Rotated rotated = (Rotated) outcome;
        assertThat(rotated.kind()).isEqualTo(RotationKind.FRESH);
        IssuedSession next = rotated.session();
        assertThat(next.sessionId()).isEqualTo(first.sessionId());
        assertThat(next.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(next.refreshExpiresAt()).isEqualTo(Instant.parse("2026-10-12T17:15:00Z"));
        assertThat(next.accessExpiresAt()).isEqualTo(Instant.parse("2026-10-12T09:25:00Z"));

        List<Record> tokens = tokensOf(first.sessionId());
        assertThat(tokens).hasSize(2);
        Record consumed = tokens.get(0);
        Record successor = tokens.get(1);
        assertThat(consumed.get("token_hash", String.class))
                .isEqualTo(RefreshTokenHash.of(first.refreshToken()).value());
        assertThat(instantOf(consumed, "consumed_at"))
                .isEqualTo(Instant.parse("2026-10-12T09:15:00Z"));
        assertThat(consumed.get("consumed_user_agent_fingerprint", String.class))
                .isEqualTo(UserAgentFingerprint.of(AGENT_ONE).value());
        assertThat(consumed.get("replaced_by", UUID.class)).isEqualTo(successor.get("id", UUID.class));
        assertThat(successor.get("token_hash", String.class))
                .isEqualTo(RefreshTokenHash.of(next.refreshToken()).value());
        assertThat(successor.get("consumed_at")).as("the successor is the one open token").isNull();
        assertThat(instantOf(successor, "issued_at"))
                .isEqualTo(Instant.parse("2026-10-12T09:15:00Z"));
        assertThat(instantOf(successor, "expires_at"))
                .isEqualTo(Instant.parse("2026-10-12T17:15:00Z"));
        assertThat(consumed.get("successor_token_ciphertext", String.class))
                .as("the successor is stored encrypted for the grace window of 4.3").startsWith("v1:");
        assertThat(instantOf(familyRow(first.sessionId()), "last_used_at"))
                .isEqualTo(Instant.parse("2026-10-12T09:15:00Z"));

        assertThat(verifierAt("2026-10-12T09:15:00Z").verifyAccess(next.accessToken().compact())
                .sessionId()).isEqualTo(first.sessionId());
        assertThat(auditRows(ACTION_REFRESHED)).singleElement().satisfies(row -> {
            assertThat(row.entityType()).isEqualTo("identity.refresh_token_family");
            assertThat(row.entityId()).isEqualTo(first.sessionId().toString());
            assertThat(row.actorId()).isEqualTo(ana.value());
            assertThat(row.actorKind()).isEqualTo("staff");
            assertThat(row.outcome()).isEqualTo("success");
            assertThat(JSON.readTree(row.afterValue()).get("rotation").asString())
                    .isEqualTo("fresh");
        });
    }

    @Test
    void aRotationCanFollowARotationAndTheNewTokenCarriesTheSecondFactorOfTheFamily() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, true);
        IssuedSession first = issueSession(NINE).execute(context(),
                commandFrom(ana, true, AGENT_ONE, null));
        Rotated second = (Rotated) rotate("2026-10-12T09:10:00Z", first.refreshToken().value(),
                AGENT_ONE);

        RefreshOutcome outcome = rotate("2026-10-12T09:20:00Z",
                second.session().refreshToken().value(), AGENT_ONE);

        assertThat(outcome).isInstanceOf(Rotated.class);
        Rotated third = (Rotated) outcome;
        assertThat(third.session().sessionId()).isEqualTo(first.sessionId());
        assertThat(verifierAt("2026-10-12T09:20:00Z").verifyAccess(
                third.session().accessToken().compact()).methods())
                .containsExactlyInAnyOrder(AuthenticationMethod.PASSWORD,
                        AuthenticationMethod.ONE_TIME_PASSWORD);
        List<Record> tokens = tokensOf(first.sessionId());
        assertThat(tokens).hasSize(3);
        assertThat(tokens.stream().filter(token -> token.get("consumed_at") == null)).hasSize(1);
        assertThat(instantOf(familyRow(first.sessionId()), "last_used_at"))
                .isEqualTo(Instant.parse("2026-10-12T09:20:00Z"));
    }

    /** Scenario I92: not a token this system could have issued, and one it never did. */
    @Test
    void aTokenThatWasNeverIssuedOrCannotBeOneIsUnknownAndChangesNothing() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        issue(NINE, ana);
        String before = storedSessions();
        List<String> presented = List.of(MADE_UP, "", MADE_UP.substring(1), MADE_UP + "a",
                "=" + MADE_UP.substring(1), MADE_UP.substring(1) + " ");

        for (String value : presented) {
            assertThat(rotate("2026-10-12T09:05:00Z", value, AGENT_ONE))
                    .as("presenting '%s'", value)
                    .isEqualTo(new Rejected(RefreshRejection.UNKNOWN_TOKEN));
        }

        assertThat(storedSessions()).isEqualTo(before);
        List<AuditRowSnapshot> entries = auditRows(ACTION_REJECTED);
        assertThat(entries).hasSize(presented.size()).allSatisfy(row -> {
            assertThat(row.entityId()).isEqualTo("unknown-token");
            assertThat(row.actorId()).isNull();
            assertThat(row.outcome()).isEqualTo("denied");
            assertThat(JSON.readTree(row.afterValue()).get("reason").asString())
                    .isEqualTo("unknown_token");
        });
    }

    /** Scenario I93. */
    @Test
    void aLiveTokenOfARevokedFamilyIsRejectedAndTheFamilyIsLeftAsItWas() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, "2026-10-12T08:30:00Z",
                "password_change");
        PlainRefreshToken token = seedOpenToken(family, EIGHT, "2026-10-12T16:00:00Z");
        String before = storedSessions();

        RefreshOutcome outcome = rotate("2026-10-12T09:00:00Z", token.value(), AGENT_ONE);

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.FAMILY_REVOKED));
        assertThat(storedSessions()).isEqualTo(before);
        assertThat(rejectionReasons()).containsExactly("family_revoked");
        assertThat(auditRows(ACTION_REJECTED).get(0).entityId()).isEqualTo(family.toString());
    }

    /** Scenario I94: the row-level policy hides the token, so it is a token nobody issued. */
    @Test
    void aTokenOfAnotherInstitutionIsNotVisibleToTheSessionOfThisOne() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession session = issue(NINE, ana);
        InstitutionId other = new InstitutionId(UUID.randomUUID());
        String before = storedSessions();

        RefreshOutcome outcome = rotation(fixedClock("2026-10-12T09:05:00Z"),
                new JooqAuditLogWriter(dsl), other).execute(contextOf(other),
                new RotateRefreshTokenCommand(session.refreshToken().value(), AGENT_ONE));

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.UNKNOWN_TOKEN));
        assertThat(storedSessions()).isEqualTo(before);
    }

    /** Scenario I109, both executions, and I113: the expiry is not a reuse and touches no other family. */
    @Test
    void aFamilyIdleForThirtyMinutesIsRejectedAtTheBorderAndNotOneSecondBefore() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID early = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        PlainRefreshToken earlyToken = seedOpenToken(early, EIGHT, "2026-10-12T16:00:00Z");
        UUID idle = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        PlainRefreshToken idleToken = seedOpenToken(idle, EIGHT, "2026-10-12T16:00:00Z");
        UUID bystander = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        seedOpenToken(bystander, EIGHT, "2026-10-12T16:00:00Z");
        String bystanderBefore = rowsOf(bystander);

        assertThat(rotate("2026-10-12T08:29:59Z", earlyToken.value(), AGENT_ONE))
                .isInstanceOf(Rotated.class);
        String idleBefore = rowsOf(idle);
        assertThat(rotate("2026-10-12T08:30:00Z", idleToken.value(), AGENT_ONE))
                .isEqualTo(new Rejected(RefreshRejection.FAMILY_EXPIRED));

        assertThat(rowsOf(idle)).as("an expiry changes nothing").isEqualTo(idleBefore);
        assertThat(familyRow(idle).get("revoked_at")).isNull();
        assertThat(familyRow(idle).get("revoked_reason")).isNull();
        assertThat(rowsOf(bystander)).isEqualTo(bystanderBefore);
        assertThat(rejectionReasons()).containsExactly("family_expired");
    }

    /** Scenarios I110 and I111. */
    @Test
    void theAbsoluteEndClipsTheNewTokenAndRejectsAtTheEndItself() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID clipped = seedFamily(ana, false, EIGHT, "2026-10-12T19:50:00Z", TWENTY, null, null);
        PlainRefreshToken clippedToken = seedOpenToken(clipped, "2026-10-12T19:45:00Z", TWENTY);
        UUID ended = seedFamily(ana, false, EIGHT, "2026-10-12T19:59:00Z", TWENTY, null, null);
        PlainRefreshToken endedToken = seedOpenToken(ended, "2026-10-12T19:45:00Z", TWENTY);

        RefreshOutcome last = rotate("2026-10-12T19:59:59Z", clippedToken.value(), AGENT_ONE);
        RefreshOutcome atTheEnd = rotate(TWENTY, endedToken.value(), AGENT_ONE);

        assertThat(last).isInstanceOf(Rotated.class);
        assertThat(((Rotated) last).session().refreshExpiresAt()).isEqualTo(Instant.parse(TWENTY));
        assertThat(instantOf(tokensOf(clipped).get(1), "expires_at"))
                .isEqualTo(Instant.parse(TWENTY));
        assertThat(atTheEnd).isEqualTo(new Rejected(RefreshRejection.FAMILY_EXPIRED));
    }

    /** Scenario I112. */
    @Test
    void aTokenIsRejectedAtItsOwnExpiryWhileItsFamilyIsAlive() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, "2026-10-12T15:59:50Z", TWENTY, null, null);
        PlainRefreshToken token = seedOpenToken(family, EIGHT, "2026-10-12T16:00:00Z");
        String before = storedSessions();

        RefreshOutcome outcome = rotate("2026-10-12T16:00:00Z", token.value(), AGENT_ONE);

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.TOKEN_EXPIRED));
        assertThat(storedSessions()).isEqualTo(before);
        assertThat(rejectionReasons()).containsExactly("token_expired");
    }

    /** Scenario I142, both cases: the expiry wins over the reuse, and it empties the successor. */
    @Test
    void aConsumedTokenPresentedToAnExpiredFamilyIsAnExpiryAndEmptiesTheEncryptedSuccessor() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID idle = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        ConsumedSeed idleSeed = seedConsumedToken(idle, "2026-10-12T07:00:00Z",
                "2026-10-12T15:00:00Z", EIGHT, AGENT_ONE, true);
        UUID ending = seedFamily(ana, false, EIGHT, EIGHT, "2026-10-12T08:00:30Z", null, null);
        ConsumedSeed endingSeed = seedConsumedToken(ending, "2026-10-12T07:00:00Z",
                "2026-10-12T15:00:00Z", EIGHT, AGENT_ONE, true);
        assertThat(tokensOf(idle).get(0).get("successor_token_ciphertext", String.class))
                .as("the encrypted successor is in the row before the presentation").isNotNull();

        RefreshOutcome idleOutcome = rotate("2026-10-12T08:30:00Z", idleSeed.consumed().value(),
                AGENT_ONE);
        RefreshOutcome endingOutcome = rotate("2026-10-12T08:00:30Z",
                endingSeed.consumed().value(), AGENT_ONE);

        assertThat(idleOutcome).isEqualTo(new Rejected(RefreshRejection.FAMILY_EXPIRED));
        assertThat(endingOutcome).isEqualTo(new Rejected(RefreshRejection.FAMILY_EXPIRED));
        for (UUID family : List.of(idle, ending)) {
            assertThat(familyRow(family).get("revoked_at")).as("an expiry is not a revocation")
                    .isNull();
            assertThat(tokensOf(family)).hasSize(2).allSatisfy(token ->
                    assertThat(token.get("successor_token_ciphertext")).isNull());
        }
    }

    /** Scenario I145: consumption is evaluated before the token's own expiry. */
    @Test
    void aConsumedTokenThatAlsoExpiredByItselfInALiveFamilyIsAReuse() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, "2026-10-12T16:30:00Z", TWENTY, null, null);
        ConsumedSeed seed = seedConsumedToken(family, EIGHT, "2026-10-12T16:00:00Z",
                "2026-10-12T08:01:00Z", AGENT_ONE, false);

        RefreshOutcome outcome = rotate("2026-10-12T16:30:10Z", seed.consumed().value(), AGENT_ONE);

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
    }

    /** Scenario I114: the authenticated request has no way to write in this change. */
    @Test
    void authenticatedRequestsDoNotWriteLastUsedAtOrAnyRow() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession session = issue(NINE, ana);
        String before = storedSessions();
        int entries = auditRows().size();

        for (String minute : List.of("09:01", "09:02", "09:03", "09:04", "09:05")) {
            String at = "2026-10-12T" + minute + ":00Z";
            assertThat(verifierAt(at).verifyAccess(session.accessToken().compact()).sessionId())
                    .isEqualTo(session.sessionId());
        }

        assertThat(instantOf(familyRow(session.sessionId()), "last_used_at"))
                .isEqualTo(Instant.parse(NINE));
        assertThat(storedSessions()).isEqualTo(before);
        assertThat(auditRows()).hasSize(entries);
    }

    /** Scenario I121 and the absence of binding: another agent rotates a live token in a live family. */
    @Test
    void anotherAgentRotatesALiveTokenAndTheFamilyIsNotRevoked() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession session = issueSession(NINE).execute(context(),
                commandFrom(ana, false, AGENT_ONE, "203.0.113.9"));

        RefreshOutcome outcome = rotate("2026-10-12T09:20:00Z", session.refreshToken().value(),
                AGENT_TWO);

        assertThat(outcome).isInstanceOf(Rotated.class);
        assertThat(familyRow(session.sessionId()).get("revoked_at")).isNull();
        assertThat(tokensOf(session.sessionId()).get(0)
                .get("consumed_user_agent_fingerprint", String.class))
                .as("the agent that presented it is recorded, not the one that opened the session")
                .isEqualTo(UserAgentFingerprint.of(AGENT_TWO).value());
    }

    /** The absence of any purge: a revoked family and an expired token are still there after a rejection. */
    @Test
    void expiredAndRevokedRowsAreKept() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, "2026-10-10T08:00:00Z", "2026-10-10T08:00:00Z",
                "2026-10-10T20:00:00Z", "2026-10-10T09:00:00Z", "password_change");
        PlainRefreshToken token = seedOpenToken(family, "2026-10-10T08:00:00Z",
                "2026-10-10T16:00:00Z");

        RefreshOutcome outcome = rotate("2026-10-12T08:00:00Z", token.value(), AGENT_ONE);

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.FAMILY_REVOKED));
        assertThat(familyRow(family).get("revoked_reason", String.class)).isEqualTo("password_change");
        assertThat(tokensOf(family)).hasSize(1);
    }

    /** One transaction: if the entry cannot be written, nothing of the rotation exists. */
    @Test
    void aFailureAfterTheRotationIsWrittenLeavesTheTokenOpenAndNoSuccessor() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession session = issue(NINE, ana);
        String before = storedSessions();
        JooqAuditLogWriter real = new JooqAuditLogWriter(dsl);
        AuditLogWriter failing = entry -> {
            real.append(entry);
            throw new IllegalStateException("the process failed before the commit");
        };

        assertThatThrownBy(() -> rotation(fixedClock("2026-10-12T09:10:00Z"), failing,
                institutionId).execute(context(), new RotateRefreshTokenCommand(
                session.refreshToken().value(), AGENT_ONE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the process failed before the commit");

        assertThat(storedSessions()).isEqualTo(before);
        assertThat(auditRows(ACTION_REFRESHED)).isEmpty();
    }

    /** No secret reaches the trail, neither on a rotation nor on a rejection. */
    @Test
    void theEntriesOfARotationAndOfARejectionCarryNoSecret() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession first = issue(NINE, ana);
        Rotated rotated = (Rotated) rotate("2026-10-12T09:10:00Z", first.refreshToken().value(),
                AGENT_ONE);
        rotate("2026-10-12T21:00:00Z", rotated.session().refreshToken().value(), AGENT_ONE);
        rotate("2026-10-12T09:11:00Z", MADE_UP, AGENT_ONE);

        String trail = auditRows().toString();

        assertThat(auditRows(ACTION_REFRESHED)).hasSize(1);
        assertThat(auditRows(ACTION_REJECTED)).hasSize(2);
        for (String secret : List.of(first.refreshToken().value(),
                rotated.session().refreshToken().value(), MADE_UP,
                RefreshTokenHash.of(first.refreshToken()).value(),
                RefreshTokenHash.of(rotated.session().refreshToken()).value(),
                UserAgentFingerprint.of(AGENT_ONE).value(),
                rotated.session().accessToken().compact(), first.accessToken().compact())) {
            assertThat(trail).doesNotContain(secret);
        }
    }

    /**
     * Scenario I104 (ADR-0005, test 1): a consumed token presented outside the grace window revokes
     * its family, leaves no live token (the successor included) and writes the entry of a possible
     * token leak, naming the family. The state is read from a new transaction after the rejection.
     */
    @Test
    void aConsumedTokenPresentedOutsideTheGraceWindowRevokesTheFamilyAndLeavesNoLiveToken() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession first = issue("2026-10-12T08:55:00Z", ana);
        Rotated rotated = (Rotated) rotate("2026-10-12T09:00:00Z", first.refreshToken().value(),
                AGENT_ONE);
        assertThat(liveTokenCount()).as("before the reuse the successor is live").isEqualTo(1);

        RefreshOutcome outcome = rotate("2026-10-12T09:20:00Z", first.refreshToken().value(),
                AGENT_ONE);

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        Record family = familyRow(first.sessionId());
        assertThat(family.get("revoked_reason", String.class)).isEqualTo("reuse_detected");
        assertThat(instantOf(family, "revoked_at")).isEqualTo(Instant.parse("2026-10-12T09:20:00Z"));
        assertThat(liveTokenCount()).as("not even the successor is live").isZero();
        assertThat(rotate("2026-10-12T09:21:00Z", rotated.session().refreshToken().value(),
                AGENT_ONE)).as("the successor cannot be rotated")
                .isEqualTo(new Rejected(RefreshRejection.FAMILY_REVOKED));
        assertThat(auditRows(ACTION_REUSE_DETECTED)).singleElement().satisfies(row -> {
            assertThat(row.entityType()).isEqualTo("identity.refresh_token_family");
            assertThat(row.entityId()).isEqualTo(first.sessionId().toString());
            assertThat(row.actorId()).isEqualTo(ana.value());
            assertThat(row.actorKind()).isEqualTo("staff");
            assertThat(row.outcome()).isEqualTo("denied");
            assertThat(JSON.readTree(row.afterValue()).get("familyRevoked").asBoolean()).isTrue();
        });
        assertThat(auditRows(ACTION_REJECTED)).as("the reuse is not also a plain rejection")
                .hasSize(1);
    }

    /** Scenario I105: the rejection is a returned value, so what it wrote is committed. */
    @Test
    void theRevocationAndItsEntrySurviveTheRejectionAndAreReadFromANewTransaction() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, "2026-10-12T09:15:00Z", TWENTY, null, null);
        ConsumedSeed seed = seedConsumedToken(family, EIGHT, SIXTEEN, "2026-10-12T09:00:00Z",
                AGENT_ONE, false);
        List<RefreshOutcome> outcomes = new ArrayList<>();

        assertThatCode(() -> outcomes.add(rotate("2026-10-12T09:20:00Z", seed.consumed().value(),
                AGENT_ONE))).as("a reuse is a value, never an exception").doesNotThrowAnyException();

        assertThat(outcomes).containsExactly(new Rejected(RefreshRejection.REUSE_DETECTED));
        // Both readers open their own transaction, after the rejected request has ended.
        assertThat(familyRow(family).get("revoked_reason", String.class)).isEqualTo("reuse_detected");
        assertThat(auditRows(ACTION_REUSE_DETECTED)).hasSize(1);
    }

    /** One transaction: if the entry cannot be written, the family is not revoked either. */
    @Test
    void aFailureAfterTheRevocationLeavesTheFamilyAndItsTokensAsTheyWere() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, "2026-10-12T09:15:00Z", TWENTY, null, null);
        ConsumedSeed seed = seedConsumedToken(family, EIGHT, SIXTEEN, "2026-10-12T09:00:00Z",
                AGENT_ONE, true);
        String before = storedSessions();
        JooqAuditLogWriter real = new JooqAuditLogWriter(dsl);
        AuditLogWriter failing = entry -> {
            real.append(entry);
            throw new IllegalStateException("the process failed before the commit");
        };

        assertThatThrownBy(() -> rotation(fixedClock("2026-10-12T09:20:00Z"), failing, institutionId)
                .execute(context(), new RotateRefreshTokenCommand(seed.consumed().value(),
                        AGENT_ONE))).isInstanceOf(IllegalStateException.class)
                .hasMessage("the process failed before the commit");

        assertThat(storedSessions()).isEqualTo(before);
        assertThat(familyRow(family).get("revoked_at")).isNull();
        assertThat(auditRows(ACTION_REUSE_DETECTED)).isEmpty();
    }

    /** Scenario I106: a reuse in one family touches no other family of the same account. */
    @Test
    void onlyTheFamilyWhereTheReuseHappenedIsRevoked() {
        StaffAccountId maria = seedStaffAccount("maria.lopez@colegio.edu.hn", CURRENT_PASSWORD, false);
        UUID reused = seedFamily(maria, false, EIGHT, "2026-10-12T09:15:00Z", TWENTY, null, null);
        ConsumedSeed seed = seedConsumedToken(reused, EIGHT, SIXTEEN, "2026-10-12T09:00:00Z",
                AGENT_ONE, false);
        UUID bystander = seedFamily(maria, false, EIGHT, "2026-10-12T09:15:00Z", TWENTY, null, null);
        seedOpenToken(bystander, EIGHT, SIXTEEN);
        seedOpenToken(seedFamily(maria, true, EIGHT, "2026-10-12T09:15:00Z", TWENTY, null, null),
                EIGHT, SIXTEEN);
        String bystandersBefore = rowsOfAllFamiliesExcept(reused);

        rotate("2026-10-12T09:20:00Z", seed.consumed().value(), AGENT_ONE);

        assertThat(familyRow(reused).get("revoked_reason", String.class)).isEqualTo("reuse_detected");
        assertThat(rowsOfAllFamiliesExcept(reused)).isEqualTo(bystandersBefore);
        assertThat(familyRow(bystander).get("revoked_at")).isNull();
        assertThat(liveTokenCount()).as("the two other families keep their tokens").isEqualTo(2);
    }

    /** Scenario I107: a token of a family already revoked changes nothing and is not a second reuse. */
    @Test
    void presentingATokenOfAFamilyAlreadyRevokedForReuseModifiesNothing() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession first = issue("2026-10-12T08:55:00Z", ana);
        rotate("2026-10-12T09:00:00Z", first.refreshToken().value(), AGENT_ONE);
        rotate("2026-10-12T09:20:00Z", first.refreshToken().value(), AGENT_ONE);
        String revoked = storedSessions();

        RefreshOutcome outcome = rotate("2026-10-12T09:25:00Z", first.refreshToken().value(),
                AGENT_ONE);

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.FAMILY_REVOKED));
        Record family = familyRow(first.sessionId());
        assertThat(instantOf(family, "revoked_at")).isEqualTo(Instant.parse("2026-10-12T09:20:00Z"));
        assertThat(family.get("revoked_reason", String.class)).isEqualTo("reuse_detected");
        assertThat(storedSessions()).isEqualTo(revoked);
        assertThat(auditRows(ACTION_REUSE_DETECTED)).as("no second reuse entry").hasSize(1);
        assertThat(rejectionReasons()).containsExactly("family_revoked");
    }

    /** The note of review 3.2 (S-2): a revocation for reuse empties the encrypted successors too. */
    @Test
    void aReuseEmptiesTheEncryptedSuccessorsOfEveryTokenOfTheFamily() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, "2026-10-12T09:15:00Z", TWENTY, null, null);
        ConsumedSeed older = seedConsumedToken(family, EIGHT, SIXTEEN, "2026-10-12T08:30:00Z",
                AGENT_ONE, true);
        assertThat(tokensOf(family).stream()
                .filter(token -> token.get("successor_token_ciphertext") != null)).hasSize(1);

        RefreshOutcome outcome = rotate("2026-10-12T09:20:00Z", older.consumed().value(), AGENT_TWO);

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        assertThat(tokensOf(family)).hasSize(2).allSatisfy(token ->
                assertThat(token.get("successor_token_ciphertext")).isNull());
    }

    /** Scenario I144: five rejections, five entries of denial, and not one secret in any. */
    @Test
    void everyRejectionLeavesOneDeniedEntryAndNoneCarriesASecret() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID revokedFamily = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, "2026-10-12T08:30:00Z",
                "password_change");
        PlainRefreshToken revokedToken = seedOpenToken(revokedFamily, EIGHT, SIXTEEN);
        UUID idleFamily = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        PlainRefreshToken idleToken = seedOpenToken(idleFamily, EIGHT, SIXTEEN);
        UUID expiredFamily = seedFamily(ana, false, EIGHT, "2026-10-12T15:59:50Z", TWENTY, null, null);
        PlainRefreshToken expiredToken = seedOpenToken(expiredFamily, EIGHT, SIXTEEN);
        UUID reusedFamily = seedFamily(ana, false, EIGHT, "2026-10-12T15:59:50Z", TWENTY, null, null);
        ConsumedSeed reused = seedConsumedToken(reusedFamily, EIGHT, SIXTEEN,
                "2026-10-12T08:01:00Z", AGENT_ONE, true);

        rotate("2026-10-12T16:00:00Z", MADE_UP, AGENT_ONE);
        rotate("2026-10-12T16:00:00Z", revokedToken.value(), AGENT_ONE);
        rotate("2026-10-12T16:00:00Z", idleToken.value(), AGENT_ONE);
        rotate("2026-10-12T16:00:00Z", expiredToken.value(), AGENT_ONE);
        rotate("2026-10-12T16:00:00Z", reused.consumed().value(), AGENT_TWO);

        assertThat(rejectionReasons()).containsExactly("unknown_token", "family_revoked",
                "family_expired", "token_expired");
        assertThat(auditRows(ACTION_REJECTED)).allSatisfy(row ->
                assertThat(row.outcome()).isEqualTo("denied"));
        assertThat(auditRows(ACTION_REJECTED).get(0).entityId()).isEqualTo("unknown-token");
        assertThat(auditRows(ACTION_REUSE_DETECTED)).singleElement().satisfies(row -> {
            assertThat(row.entityId()).isEqualTo(reusedFamily.toString());
            assertThat(row.outcome()).isEqualTo("denied");
        });
        String trail = auditRows().toString();
        for (String secret : List.of(MADE_UP, revokedToken.value(), idleToken.value(),
                expiredToken.value(), reused.consumed().value(), reused.successor().value(),
                RefreshTokenHash.of(reused.consumed()).value(),
                RefreshTokenHash.of(reused.successor()).value(),
                UserAgentFingerprint.of(AGENT_ONE).value(),
                UserAgentFingerprint.of(AGENT_TWO).value(), "v1:AAAA")) {
            assertThat(trail).doesNotContain(secret);
        }
    }

    /** The absence of any notice to the holder: this change delivers the revocation and its entry. */
    @Test
    void aReuseNotifiesNobodyAndNoEntryNamesANotification() {
        StaffAccountId maria = seedStaffAccount("maria.lopez@colegio.edu.hn", CURRENT_PASSWORD, false);
        UUID family = seedFamily(maria, false, EIGHT, "2026-10-12T09:15:00Z", TWENTY, null, null);
        ConsumedSeed seed = seedConsumedToken(family, EIGHT, SIXTEEN, "2026-10-12T09:00:00Z",
                AGENT_ONE, false);

        rotate("2026-10-12T09:20:00Z", seed.consumed().value(), AGENT_ONE);

        String trail = auditRows().toString().toLowerCase(Locale.ROOT);
        assertThat(trail).doesNotContain("notif", "mail", "correo", "email", "delivery", "sent");
        assertThat(RotateRefreshToken.class.getConstructors()[0].getParameterTypes())
                .as("nothing that can send is wired into the rotation")
                .allSatisfy(type -> assertThat(type.getSimpleName().toLowerCase(Locale.ROOT))
                        .doesNotContain("sender", "notif", "mail", "link"));
    }

    private List<String> rejectionReasons() {
        return auditRows(ACTION_REJECTED).stream()
                .map(row -> JSON.readTree(row.afterValue()).get("reason").asString()).toList();
    }

    /** Every family but one, with its tokens, as text: to tell whether anything touched the others. */
    private String rowsOfAllFamiliesExcept(UUID excluded) {
        return transactionRunner().execute(context(), () -> dsl.fetch("""
                select id from identity_refresh_token_family where institution_id = ? and id <> ?
                 order by id
                """, institutionId.value(), excluded).getValues("id", UUID.class)).stream()
                .map(this::rowsOf).reduce("", String::concat);
    }

    /** One family and its tokens as text, to tell whether a rejection touched them. */
    private String rowsOf(UUID familyId) {
        return familyRow(familyId).formatCSV() + tokensOf(familyId).stream()
                .map(Record::formatCSV).reduce("", String::concat);
    }
}
