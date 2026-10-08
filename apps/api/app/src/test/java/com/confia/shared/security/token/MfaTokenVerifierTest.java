package com.confia.shared.security.token;

import static com.confia.shared.security.token.AccessTokenFixtures.ACCOUNT;
import static com.confia.shared.security.token.AccessTokenFixtures.FOREIGN;
import static com.confia.shared.security.token.AccessTokenFixtures.FOREIGN_KID;
import static com.confia.shared.security.token.AccessTokenFixtures.INSTITUTION;
import static com.confia.shared.security.token.AccessTokenFixtures.NOW;
import static com.confia.shared.security.token.AccessTokenFixtures.SESSION;
import static com.confia.shared.security.token.AccessTokenFixtures.TOKEN_ID;
import static com.confia.shared.security.token.AccessTokenFixtures.accessClaims;
import static com.confia.shared.security.token.AccessTokenFixtures.mfaClaims;
import static com.confia.shared.security.token.AccessTokenFixtures.signed;
import static com.confia.shared.security.token.AccessTokenFixtures.signedBy;
import static com.confia.shared.security.token.AccessTokenFixtures.verifierAt;
import static com.confia.shared.security.token.AccessTokenFixtures.with;
import static com.confia.shared.security.token.AccessTokenFixtures.without;
import static com.confia.shared.security.token.JwsFixtures.CURRENT_KID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The verifier of the restricted MFA token, and the two kinds of token that never stand for each other
 * (session-tokens-and-web-layer design.md, decision 4; scenarios I74, I76 and I77). As in {@link
 * AccessTokenVerifierTest}, every token is built with the JDK's own signer over hand-edited claims, so
 * each case is a token an attacker holding a valid signature could produce, and each rejection asserts
 * its exact {@link TokenRejection}.
 */
class MfaTokenVerifierTest {

    private static final Map<String, Object> VALID_ACCESS = accessClaims(NOW);
    private static final AccessTokenVerifier VERIFIER = verifierAt(NOW);
    private static final List<String> MFA_NAMES = List.of("iss", "aud", "sub", "exp", "iat", "jti",
            "tenant", "amr", "purpose");

    @Test
    void aValidRestrictedTokenIsAcceptedByTheMfaVerifierWithItsClaims() {
        for (MfaPurpose purpose : MfaPurpose.values()) {
            MfaTokenClaims claims = VERIFIER.verifyMfa(signed(mfaClaims(NOW, purpose.claimValue())));

            assertThat(claims.accountId()).isEqualTo(ACCOUNT);
            assertThat(claims.institutionId()).isEqualTo(INSTITUTION);
            assertThat(claims.tokenId()).isEqualTo(TOKEN_ID);
            assertThat(claims.purpose()).isEqualTo(purpose);
            assertThat(claims.issuedAt()).isEqualTo(NOW);
            assertThat(claims.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        }
    }

    @Test
    void theRestrictedTokenIsRejectedByTheAccessVerifierAndTheAccessTokenByTheMfaVerifier() {
        String restricted = signed(mfaClaims(NOW, "mfa-verify"));
        String access = signed(VALID_ACCESS);

        assertAccessRejected(restricted, TokenRejection.CLAIMS_INVALID);
        assertMfaRejected(access, TokenRejection.CLAIMS_INVALID);
    }

    @Test
    void theRestrictedTokenIsRejectedByTheAccessVerifierForItsAudienceAlone() {
        // Every claim of an access token is correct, including sid and no purpose; only the
        // audience says restricted. The access verifier must not depend on anyone reading purpose.
        assertAccessRejected(signed(with(VALID_ACCESS, "aud", "confia-admin-mfa")),
                TokenRejection.CLAIMS_INVALID);
        // And the other way: an access audience on an otherwise complete restricted claim set.
        assertMfaRejected(signed(with(mfaClaims(NOW, "mfa-verify"), "aud", "confia-admin")),
                TokenRejection.CLAIMS_INVALID);
    }

    @Test
    void theAccessVerifierRefusesARestrictedTokenWhateverClaimsAreAddedToMakeItLookLikeAnAccessOne() {
        Map<String, Object> disguised = with(with(mfaClaims(NOW, "mfa-verify"), "sid",
                SESSION.toString()), "exp", NOW.getEpochSecond() + 600);

        assertAccessRejected(signed(disguised), TokenRejection.CLAIMS_INVALID);
        assertAccessRejected(signed(with(disguised, "aud", "confia-admin")),
                TokenRejection.CLAIMS_INVALID);
    }

    @ParameterizedTest(name = "[{index}] restricted without {0}")
    @ValueSource(strings = {"iss", "aud", "sub", "exp", "iat", "jti", "tenant", "amr", "purpose"})
    void aRestrictedTokenMissingAnyOfItsNineClaimsIsInvalid(String name) {
        assertMfaRejected(signed(without(mfaClaims(NOW, "mfa-verify"), name)),
                TokenRejection.CLAIMS_INVALID);
    }

    static Stream<Arguments> restrictedClaimsOfTheWrongTypeOrValue() {
        return Stream.of(
                Arguments.of("a purpose outside the closed set", "purpose", "admin"),
                Arguments.of("a purpose in upper case", "purpose", "MFA-VERIFY"),
                Arguments.of("a purpose with a trailing space", "purpose", "mfa-verify "),
                Arguments.of("an empty purpose", "purpose", ""),
                Arguments.of("a null purpose", "purpose", null),
                Arguments.of("a numeric purpose", "purpose", 1),
                Arguments.of("an array purpose", "purpose", List.of("mfa-verify")),
                Arguments.of("a sid", "sid", SESSION.toString()),
                Arguments.of("amr with the second factor", "amr", List.of("pwd", "otp")),
                Arguments.of("amr with only otp", "amr", List.of("otp")),
                Arguments.of("an access lifetime", "exp", NOW.getEpochSecond() + 600),
                Arguments.of("a lifetime of 301 s", "exp", NOW.getEpochSecond() + 301),
                Arguments.of("a lifetime of 299 s", "exp", NOW.getEpochSecond() + 299),
                Arguments.of("an audience of the portal", "aud", "confia-portal"),
                Arguments.of("an audience as an array", "aud", List.of("confia-admin-mfa")),
                Arguments.of("an issuer of the portal", "iss", "confia-portal"),
                Arguments.of("a tenant that is not a UUID", "tenant", "colegio"),
                Arguments.of("an extra permissions claim", "permissions", List.of()),
                Arguments.of("an extra role claim", "role", "admin"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("restrictedClaimsOfTheWrongTypeOrValue")
    void aRestrictedClaimOfTheWrongTypeOrValueMakesTheTokenInvalid(String name, String claim,
            Object value) {
        assertMfaRejected(signed(with(mfaClaims(NOW, "mfa-verify"), claim, value)),
                TokenRejection.CLAIMS_INVALID);
    }

    @Test
    void theRestrictedTokenFollowsTheSameTimeRulesWithAThreeHundredSecondLife() {
        String token = signed(mfaClaims(NOW, "mfa-enroll"));
        Instant exp = NOW.plusSeconds(300);

        assertThat(verifierAt(exp.minusSeconds(1)).verifyMfa(token).purpose())
                .isEqualTo(MfaPurpose.MFA_ENROLL);
        assertRejected(() -> verifierAt(exp).verifyMfa(token), TokenRejection.EXPIRED);
        assertRejected(() -> verifierAt(exp.plusSeconds(200)).verifyMfa(token),
                TokenRejection.EXPIRED);
        Map<String, Object> ahead = with(with(mfaClaims(NOW, "mfa-enroll"), "iat",
                NOW.getEpochSecond() + 61), "exp", NOW.getEpochSecond() + 361);
        assertMfaRejected(signed(ahead), TokenRejection.CLAIMS_INVALID);
        Map<String, Object> aheadByAMinute = with(with(mfaClaims(NOW, "mfa-enroll"), "iat",
                NOW.getEpochSecond() + 60), "exp", NOW.getEpochSecond() + 360);
        assertThat(VERIFIER.verifyMfa(signed(aheadByAMinute)).issuedAt())
                .isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void anExpiredRestrictedTokenIsExpiredOnlyWhenEveryOtherRuleHolds() {
        AccessTokenVerifier late = verifierAt(NOW.plusSeconds(1000));

        assertRejected(() -> late.verifyMfa(signed(mfaClaims(NOW, "mfa-verify"))),
                TokenRejection.EXPIRED);
        assertRejected(() -> late.verifyMfa(signed(mfaClaims(NOW, "admin"))),
                TokenRejection.CLAIMS_INVALID);
        assertRejected(() -> late.verifyMfa(signed(with(mfaClaims(NOW, "mfa-verify"), "aud",
                "confia-portal"))), TokenRejection.CLAIMS_INVALID);
        assertRejected(() -> late.verifyMfa(signedBy(CURRENT_KID, FOREIGN.getPrivate(),
                mfaClaims(NOW, "mfa-verify"))), TokenRejection.BAD_SIGNATURE);
    }

    @Test
    void theRestrictedTokenKeepsTheCodecRulesForKeysAndSignatures() {
        Map<String, Object> claims = mfaClaims(NOW, "mfa-verify");

        assertMfaRejected(signedBy(FOREIGN_KID, FOREIGN.getPrivate(), claims),
                TokenRejection.UNKNOWN_HEADER);
        assertMfaRejected(signedBy(CURRENT_KID, FOREIGN.getPrivate(), claims),
                TokenRejection.BAD_SIGNATURE);
        assertMfaRejected(signed(claims).substring(0, signed(claims).length() - 2),
                TokenRejection.MALFORMED);
    }

    @Test
    void aRestrictedClaimSetInAnyMemberOrderIsTheSameToken() {
        // Member order is the issuer's choice and carries no meaning: the verifier reads by name.
        Map<String, Object> reversed = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(MFA_NAMES);
        Collections.reverse(names);
        Map<String, Object> source = mfaClaims(NOW, "mfa-verify");
        names.forEach(name -> reversed.put(name, source.get(name)));

        assertThat(VERIFIER.verifyMfa(signed(reversed)).purpose()).isEqualTo(MfaPurpose.MFA_VERIFY);
    }

    @Test
    void garbageIsAlwaysATokenRejectionAndNeverAnotherException() {
        for (String input : new String[] {null, "", "   ", "A".repeat(10_000), "\0\0\0", "..."}) {
            assertThatThrownBy(() -> VERIFIER.verifyMfa(input))
                    .isExactlyInstanceOf(TokenRejectedException.class);
        }
    }

    private static void assertAccessRejected(String token, TokenRejection expected) {
        assertRejected(() -> VERIFIER.verifyAccess(token), expected);
    }

    private static void assertMfaRejected(String token, TokenRejection expected) {
        assertRejected(() -> VERIFIER.verifyMfa(token), expected);
    }

    private static void assertRejected(Runnable verification, TokenRejection expected) {
        assertThatThrownBy(verification::run).isExactlyInstanceOf(TokenRejectedException.class)
                .satisfies(e -> {
                    TokenRejectedException rejected = (TokenRejectedException) e;
                    assertThat(rejected.rejection()).isEqualTo(expected);
                    assertThat(rejected.getMessage()).isEqualTo(expected.name());
                    assertThat(rejected.getCause()).isNull();
                });
    }
}
