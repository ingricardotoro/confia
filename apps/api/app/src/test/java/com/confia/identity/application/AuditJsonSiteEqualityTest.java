package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.AuthenticationResult.RejectionReason;
import com.confia.identity.domain.PasswordResetRejectionReason;
import com.confia.identity.domain.RefreshRejection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Specs/audit-trail, scenario AT01 (session-endpoints-cookie-and-csrf design.md, decision 17): each
 * of the 16 sites of the 8 identity use cases that used to assemble the detail JSON of an audit
 * entry by concatenation writes, through {@code AuditJson}, exactly the same string. The expected
 * side of every comparison is the old expression copied literally from the code as it was before
 * the migration; the actual side calls the real production site (package-private for this test).
 * A drifting byte would break the hash chain, because the detail JSON enters the preimage.
 */
class AuditJsonSiteEqualityTest {

    private static final int[] COUNTERS = {0, 1, 2, 3, Integer.MAX_VALUE};
    private static final long[] LONG_COUNTERS = {0L, 1L, Integer.MAX_VALUE, Long.MAX_VALUE};
    private static final Instant WHOLE_SECOND = Instant.parse("2026-10-21T10:15:30Z");
    private static final Instant WITH_FRACTION = Instant.parse("2026-10-21T10:15:30.123456789Z");
    private static final Instant EPOCH = Instant.EPOCH;

    /** One comparison: the old expression's result and what the production site writes now. */
    private record Case(String input, String old, Supplier<String> now) {
    }

    private record Site(String name, List<Case> cases) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Site> sites() {
        return Stream.of(
                authenticateBackoff(), authenticateRejection(), consumeLowCodes(),
                issuePasswordResetRateLimited(), issuePasswordResetIssued(), issueSessionStarted(),
                verifyTotpBackoff(), requestResetScheduled(), requestResetAccountNotFound(),
                resetSessionsRevoked(), resetCompleted(), resetRejected(), rotateFresh(),
                rotateGrace(), rotateFamilyRevoked(), rotateRejected());
    }

    @Test
    void thereAreExactlySixteenSites() {
        assertThat(sites().count()).isEqualTo(16L);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sites")
    void theMigratedSiteWritesTheSameStringAsTheOldConcatenation(Site site) {
        assertThat(site.cases()).as("the site has edge values to compare").isNotEmpty();
        for (Case current : site.cases()) {
            assertThat(current.now().get()).as(site.name() + " for " + current.input())
                    .isEqualTo(current.old());
        }
    }

    // --- AuthenticateWithPassword (2) -------------------------------------------------------

    private static Site authenticateBackoff() {
        List<Case> cases = new ArrayList<>();
        for (int ordinal : COUNTERS) {
            for (long seconds : LONG_COUNTERS) {
                Duration delay = Duration.ofSeconds(seconds);
                int attemptOrdinal = ordinal;
                String old = "{\"consecutiveFailures\":" + attemptOrdinal + ",\"delaySeconds\":"
                        + delay.toSeconds() + "}";
                cases.add(new Case(ordinal + "/" + seconds, old,
                        () -> AuthenticateWithPassword.backoffCycleAfterValue(attemptOrdinal, delay)));
            }
        }
        return new Site("AuthenticateWithPassword.backoffCycleAfterValue", cases);
    }

    private static Site authenticateRejection() {
        List<Case> cases = new ArrayList<>();
        for (RejectionReason reason : RejectionReason.values()) {
            String kebabCase = switch (reason) {
                case INVALID_PASSWORD -> "invalid-password";
                case ACCOUNT_NOT_FOUND -> "account-not-found";
            };
            cases.add(new Case(reason.name(), "{\"reason\":\"" + kebabCase + "\"}",
                    () -> AuthenticateWithPassword.rejectionReasonJson(reason)));
        }
        return new Site("AuthenticateWithPassword.rejectionReasonJson", cases);
    }

    // --- ConsumeRecoveryCode (1) ------------------------------------------------------------

    private static Site consumeLowCodes() {
        List<Case> cases = new ArrayList<>();
        for (long remaining : LONG_COUNTERS) {
            cases.add(new Case(Long.toString(remaining),
                    "{\"remainingUnusedCodes\":" + remaining + "}",
                    () -> ConsumeRecoveryCode.lowCodesAfterValue(remaining)));
        }
        return new Site("ConsumeRecoveryCode.lowCodesAfterValue", cases);
    }

    // --- IssuePasswordResetToken (2) --------------------------------------------------------

    private static Site issuePasswordResetRateLimited() {
        List<Case> cases = new ArrayList<>();
        for (long issued : LONG_COUNTERS) {
            cases.add(new Case(Long.toString(issued),
                    "{\"reason\":\"rate-limit\",\"issuedInWindow\":" + issued + "}",
                    () -> IssuePasswordResetToken.rateLimitedAfterValue(issued)));
        }
        return new Site("IssuePasswordResetToken.rateLimitedAfterValue", cases);
    }

    private static Site issuePasswordResetIssued() {
        List<Case> cases = new ArrayList<>();
        for (Instant expiresAt : List.of(EPOCH, WHOLE_SECOND, WITH_FRACTION)) {
            for (int superseded : COUNTERS) {
                String old = "{\"expiresAt\":\"" + expiresAt + "\",\"supersededCount\":"
                        + superseded + "}";
                cases.add(new Case(expiresAt + "/" + superseded, old,
                        () -> IssuePasswordResetToken.issuedAfterValue(expiresAt, superseded)));
            }
        }
        return new Site("IssuePasswordResetToken.issuedAfterValue", cases);
    }

    // --- IssueSession (1) -------------------------------------------------------------------

    private static Site issueSessionStarted() {
        List<Case> cases = new ArrayList<>();
        for (boolean secondFactor : new boolean[] {true, false}) {
            cases.add(new Case(Boolean.toString(secondFactor),
                    "{\"secondFactor\":" + secondFactor + "}",
                    () -> IssueSession.startedAfterValue(secondFactor)));
        }
        return new Site("IssueSession.startedAfterValue", cases);
    }

    // --- VerifyTotpCode (1) -----------------------------------------------------------------

    private static Site verifyTotpBackoff() {
        List<Case> cases = new ArrayList<>();
        for (int ordinal : COUNTERS) {
            for (long seconds : LONG_COUNTERS) {
                Duration delay = Duration.ofSeconds(seconds);
                int attemptOrdinal = ordinal;
                String old = "{\"consecutiveFailures\":" + attemptOrdinal + ",\"delaySeconds\":"
                        + delay.toSeconds() + "}";
                cases.add(new Case(ordinal + "/" + seconds, old,
                        () -> VerifyTotpCode.backoffCycleAfterValue(attemptOrdinal, delay)));
            }
        }
        return new Site("VerifyTotpCode.backoffCycleAfterValue", cases);
    }

    // --- RequestPasswordReset (2) -----------------------------------------------------------

    private static Site requestResetScheduled() {
        return new Site("RequestPasswordReset.AFTER_ISSUANCE_SCHEDULED", List.of(
                new Case("constant", "{\"outcome\":\"issuance-scheduled\"}",
                        () -> RequestPasswordReset.AFTER_ISSUANCE_SCHEDULED)));
    }

    private static Site requestResetAccountNotFound() {
        return new Site("RequestPasswordReset.AFTER_ACCOUNT_NOT_FOUND", List.of(
                new Case("constant", "{\"outcome\":\"account-not-found\"}",
                        () -> RequestPasswordReset.AFTER_ACCOUNT_NOT_FOUND)));
    }

    // --- ResetPasswordWithToken (3) ---------------------------------------------------------

    private static Site resetSessionsRevoked() {
        List<Case> cases = new ArrayList<>();
        for (int families : COUNTERS) {
            String old = "{\"reason\":\"" + SessionRevocationReason.PASSWORD_CHANGE.code()
                    + "\",\"families\":" + families + "}";
            cases.add(new Case(Integer.toString(families), old,
                    () -> ResetPasswordWithToken.sessionsRevokedAfterValue(families)));
        }
        return new Site("ResetPasswordWithToken.sessionsRevokedAfterValue", cases);
    }

    private static Site resetCompleted() {
        List<Case> cases = new ArrayList<>();
        for (String secondFactorUsed : List.of("none", "totp", "recovery-code")) {
            cases.add(new Case(secondFactorUsed,
                    "{\"secondFactor\":\"" + secondFactorUsed + "\"}",
                    () -> ResetPasswordWithToken.completedAfterValue(secondFactorUsed)));
        }
        return new Site("ResetPasswordWithToken.completedAfterValue", cases);
    }

    private static Site resetRejected() {
        List<Case> cases = new ArrayList<>();
        for (PasswordResetRejectionReason reason : PasswordResetRejectionReason.values()) {
            cases.add(new Case(reason.name(), "{\"reason\":\"" + reason.auditCode() + "\"}",
                    () -> ResetPasswordWithToken.reasonJson(reason)));
        }
        return new Site("ResetPasswordWithToken.reasonJson", cases);
    }

    // --- RotateRefreshToken (4) -------------------------------------------------------------

    private static Site rotateFresh() {
        return new Site("RotateRefreshToken.AFTER_FRESH", List.of(
                new Case("constant", "{\"rotation\":\"fresh\"}",
                        () -> RotateRefreshToken.AFTER_FRESH)));
    }

    private static Site rotateGrace() {
        return new Site("RotateRefreshToken.AFTER_GRACE", List.of(
                new Case("constant", "{\"rotation\":\"grace\"}",
                        () -> RotateRefreshToken.AFTER_GRACE)));
    }

    private static Site rotateFamilyRevoked() {
        return new Site("RotateRefreshToken.AFTER_FAMILY_REVOKED", List.of(
                new Case("constant", "{\"familyRevoked\":true}",
                        () -> RotateRefreshToken.AFTER_FAMILY_REVOKED)));
    }

    private static Site rotateRejected() {
        List<Case> cases = new ArrayList<>();
        for (RefreshRejection reason : RefreshRejection.values()) {
            cases.add(new Case(reason.name(), "{\"reason\":\"" + reason.auditCode() + "\"}",
                    () -> RotateRefreshToken.reasonJson(reason)));
        }
        return new Site("RotateRefreshToken.reasonJson", cases);
    }
}
