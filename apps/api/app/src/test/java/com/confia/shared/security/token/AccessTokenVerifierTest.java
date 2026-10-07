package com.confia.shared.security.token;

import static com.confia.shared.security.token.AccessTokenFixtures.ACCOUNT;
import static com.confia.shared.security.token.AccessTokenFixtures.FOREIGN;
import static com.confia.shared.security.token.AccessTokenFixtures.FOREIGN_KID;
import static com.confia.shared.security.token.AccessTokenFixtures.INSTITUTION;
import static com.confia.shared.security.token.AccessTokenFixtures.NOW;
import static com.confia.shared.security.token.AccessTokenFixtures.PREVIOUS;
import static com.confia.shared.security.token.AccessTokenFixtures.SESSION;
import static com.confia.shared.security.token.AccessTokenFixtures.TOKEN_ID;
import static com.confia.shared.security.token.AccessTokenFixtures.accessClaims;
import static com.confia.shared.security.token.AccessTokenFixtures.json;
import static com.confia.shared.security.token.AccessTokenFixtures.signed;
import static com.confia.shared.security.token.AccessTokenFixtures.signedBy;
import static com.confia.shared.security.token.AccessTokenFixtures.signedText;
import static com.confia.shared.security.token.AccessTokenFixtures.verifierAt;
import static com.confia.shared.security.token.AccessTokenFixtures.with;
import static com.confia.shared.security.token.AccessTokenFixtures.without;
import static com.confia.shared.security.token.JwsFixtures.CURRENT_KID;
import static com.confia.shared.security.token.JwsFixtures.PREVIOUS_KID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
 * The verifier of the administrative access token
 * (session-tokens-and-web-layer design.md, decision 4; scenarios I58 to I73 and I139).
 * Every token is built here with the JDK's own signer over hand-edited claims, so each case is a token
 * an attacker holding a valid signature could produce: the claim rules, and not the signature, are
 * what rejects it. Each rejection asserts its exact {@link TokenRejection}, because the order in which
 * the rules apply is part of the contract: an expired token is {@code EXPIRED} only when everything
 * else about it is intact.
 */
class AccessTokenVerifierTest {

    private static final Map<String, Object> VALID = accessClaims(NOW);
    /** The last second of 9999-12-31, the end of the date range a claim may hold. */
    private static final long LAST_EPOCH_SECOND = 253_402_300_799L;
    private static final BigInteger TWO_TO_THE_64 = BigInteger.TWO.pow(64);
    private static final AccessTokenVerifier VERIFIER = verifierAt(NOW);
    private static final List<String> ACCESS_NAMES = List.of("iss", "aud", "sub", "exp", "iat", "jti",
            "sid", "tenant", "amr");

    // ---------------------------------------------------------------------------------------------
    // The valid token
    // ---------------------------------------------------------------------------------------------

    @Test
    void aValidAccessTokenYieldsTheActorItsClaimsDescribe() {
        AuthenticatedActor actor = VERIFIER.verifyAccess(signed(VALID));

        assertThat(actor.accountId()).isEqualTo(ACCOUNT);
        assertThat(actor.institutionId()).isEqualTo(INSTITUTION);
        assertThat(actor.sessionId()).isEqualTo(SESSION);
        assertThat(actor.tokenId()).isEqualTo(TOKEN_ID);
        assertThat(actor.methods()).containsExactly(AuthenticationMethod.PASSWORD);
        assertThat(actor.issuedAt()).isEqualTo(NOW);
        assertThat(actor.expiresAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    void amrWithTheSecondFactorYieldsBothMethods() {
        AuthenticatedActor actor = VERIFIER
                .verifyAccess(signed(with(VALID, "amr", List.of("pwd", "otp"))));

        assertThat(actor.methods()).containsExactlyInAnyOrder(AuthenticationMethod.PASSWORD,
                AuthenticationMethod.ONE_TIME_PASSWORD);
    }

    @Test
    void aTokenSignedWithThePreviousKeyOfARotationStillVerifies() {
        String token = signedBy(PREVIOUS_KID, PREVIOUS.getPrivate(), VALID);

        assertThat(VERIFIER.verifyAccess(token).accountId()).isEqualTo(ACCOUNT);
    }

    // ---------------------------------------------------------------------------------------------
    // The closed list of claims (I58, I65, I66, I67)
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] without {0}")
    @ValueSource(strings = {"iss", "aud", "sub", "exp", "iat", "jti", "sid", "tenant", "amr"})
    void aTokenMissingAnyOfTheNineDeclaredClaimsIsInvalid(String name) {
        assertAccessRejected(signed(without(VALID, name)), TokenRejection.CLAIMS_INVALID);
    }

    static Stream<Arguments> accessClaimsOfTheWrongTypeOrValue() {
        return Stream.of(
                Arguments.of("exp as a string", "exp", "1760263800"),
                Arguments.of("exp with a fraction", "exp", 1760263800.5),
                Arguments.of("exp as null", "exp", null),
                Arguments.of("exp as a boolean", "exp", true),
                Arguments.of("exp as an array", "exp", List.of(1760263800L)),
                Arguments.of("iat as a string", "iat", "1760263200"),
                Arguments.of("iat with a fraction", "iat", 1760263200.25),
                Arguments.of("iat as null", "iat", null),
                Arguments.of("exp beyond the range of a date", "exp", 9_000_000_000_000_000_000L),
                Arguments.of("iat negative", "iat", -1L),
                Arguments.of("iat of the least long", "iat", Long.MIN_VALUE),
                Arguments.of("exp one second past the last date", "exp", LAST_EPOCH_SECOND + 1),
                // A number beyond a long would wrap to its low 64 bits; only the conversion check
                // stops a value of 2^64 + a valid second from reading as that valid second.
                Arguments.of("exp of 2^64", "exp", TWO_TO_THE_64),
                Arguments.of("exp of 2^64 plus its valid value", "exp",
                        TWO_TO_THE_64.add(BigInteger.valueOf(NOW.getEpochSecond() + 600))),
                Arguments.of("iat of 2^64 plus its valid value", "iat",
                        TWO_TO_THE_64.add(BigInteger.valueOf(NOW.getEpochSecond()))),
                Arguments.of("exp of forty digits", "exp", new BigInteger("1".repeat(40))),
                Arguments.of("tenant that is not a UUID", "tenant", "colegio-1"),
                Arguments.of("tenant as a number", "tenant", 42),
                Arguments.of("tenant in upper case", "tenant", INSTITUTION.toString().toUpperCase()),
                Arguments.of("tenant without hyphens", "tenant",
                        INSTITUTION.toString().replace("-", "")),
                Arguments.of("tenant that is the empty string", "tenant", ""),
                Arguments.of("tenant padded with a space", "tenant", " " + INSTITUTION),
                Arguments.of("sid that is not a UUID", "sid", "sesion"),
                Arguments.of("sid as null", "sid", null),
                Arguments.of("sid in upper case", "sid", SESSION.toString().toUpperCase()),
                Arguments.of("sub as null", "sub", null),
                Arguments.of("sub as a number", "sub", 7),
                Arguments.of("sub that is an email", "sub", "maria.lopez@colegio.edu.hn"),
                Arguments.of("sub in a non-canonical short form", "sub", "1-1-1-1-1"),
                Arguments.of("jti that is not a UUID", "jti", "abc"),
                Arguments.of("jti as an object", "jti", Map.of("a", 1)),
                Arguments.of("amr that is not an array", "amr", "pwd"),
                Arguments.of("amr as an object", "amr", Map.of("pwd", true)),
                Arguments.of("amr as null", "amr", null),
                Arguments.of("amr empty", "amr", List.of()),
                Arguments.of("amr with a number", "amr", List.of(1)),
                Arguments.of("amr with a null element", "amr", Arrays.asList("pwd", null)),
                Arguments.of("amr with only otp", "amr", List.of("otp")),
                Arguments.of("amr in the wrong order", "amr", List.of("otp", "pwd")),
                Arguments.of("amr with a repeated method", "amr", List.of("pwd", "pwd")),
                Arguments.of("amr with a third method", "amr", List.of("pwd", "otp", "mfa")),
                Arguments.of("amr in upper case", "amr", List.of("PWD")),
                Arguments.of("iss as a number", "iss", 1),
                Arguments.of("iss as an array", "iss", List.of("confia-admin")),
                Arguments.of("iss of the portal", "iss", "confia-portal"),
                Arguments.of("iss in upper case", "iss", "CONFIA-ADMIN"),
                Arguments.of("aud as an array of one element", "aud", List.of("confia-admin")),
                Arguments.of("aud of the portal", "aud", "confia-portal"),
                Arguments.of("aud of the restricted token", "aud", "confia-admin-mfa"),
                Arguments.of("aud as null", "aud", null),
                Arguments.of("aud with a trailing space", "aud", "confia-admin "));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("accessClaimsOfTheWrongTypeOrValue")
    void aClaimOfTheWrongTypeOrValueMakesTheTokenInvalid(String name, String claim, Object value) {
        assertAccessRejected(signed(with(VALID, claim, value)), TokenRejection.CLAIMS_INVALID);
    }

    @ParameterizedTest(name = "[{index}] extra claim {0}")
    @ValueSource(strings = {"permissions", "role", "nbf", "purpose", "email", "name", "scope", "typ",
            "azp", "PERMISSIONS", "Sid", "iss "})
    void aClaimThatIsNotDeclaredMakesTheTokenInvalidWhateverItsValue(String extra) {
        assertAccessRejected(signed(with(VALID, extra, List.of())), TokenRejection.CLAIMS_INVALID);
        assertAccessRejected(signed(with(VALID, extra, "x")), TokenRejection.CLAIMS_INVALID);
        assertAccessRejected(signed(with(VALID, extra, null)), TokenRejection.CLAIMS_INVALID);
    }

    @Test
    void aRepeatedClaimIsRejectedByTheCodecAsMalformedClaims() {
        String repeated = json(VALID).replace("}", ",\"sub\":\"" + ACCOUNT + "\"}");

        assertAccessRejected(signedText(repeated), TokenRejection.MALFORMED_CLAIMS);
    }

    @Test
    void thePayloadMustBeAnObject() {
        assertAccessRejected(signedText("[1,2]"), TokenRejection.MALFORMED_CLAIMS);
        assertAccessRejected(signedText("\"confia-admin\""), TokenRejection.MALFORMED_CLAIMS);
        assertAccessRejected(signedText("{}"), TokenRejection.CLAIMS_INVALID);
    }

    @Test
    void theClaimsAreCheckedByTypeAndNeverCoercedByTheTreeApi() {
        // asLong() and asString() would turn these into the right values; the verifier must not.
        assertAccessRejected(signedText(json(VALID).replace("\"exp\":" + (NOW.getEpochSecond() + 600),
                "\"exp\":\"" + (NOW.getEpochSecond() + 600) + "\"")), TokenRejection.CLAIMS_INVALID);
        assertAccessRejected(signedText(json(VALID).replace("\"iat\":" + NOW.getEpochSecond(),
                "\"iat\":" + NOW.getEpochSecond() + ".0")), TokenRejection.CLAIMS_INVALID);
        assertAccessRejected(signedText(json(VALID).replace("\"sub\":\"" + ACCOUNT + "\"",
                "\"sub\":7")), TokenRejection.CLAIMS_INVALID);
    }

    // ---------------------------------------------------------------------------------------------
    // Time (I63, I71, I72, I139)
    // ---------------------------------------------------------------------------------------------

    @Test
    void expIsStrictItIsAcceptedBeforeAndRejectedAsExpiredAtAndAfter() {
        String token = signed(VALID);
        Instant exp = NOW.plusSeconds(600);

        assertThat(verifierAt(exp.minusSeconds(1)).verifyAccess(token).accountId()).isEqualTo(ACCOUNT);
        assertThat(verifierAt(exp.minusNanos(1)).verifyAccess(token).accountId()).isEqualTo(ACCOUNT);
        assertRejected(() -> verifierAt(exp).verifyAccess(token), TokenRejection.EXPIRED);
        assertRejected(() -> verifierAt(exp.plusNanos(1)).verifyAccess(token), TokenRejection.EXPIRED);
        assertRejected(() -> verifierAt(Instant.parse("2026-10-12T10:30:00Z")).verifyAccess(token),
                TokenRejection.EXPIRED);
    }

    @Test
    void theFirstAndTheLastSecondOfTheDateRangeAreDates() {
        // The very first second is a date: a token from 1970 is expired, never invalid.
        assertRejected(() -> verifierAt(NOW).verifyAccess(signed(with(with(VALID, "iat", 0L), "exp",
                600L))), TokenRejection.EXPIRED);

        // The very last second is a date: verified just before it, the token is accepted.
        AccessTokenVerifier atTheEnd = verifierAt(Instant.ofEpochSecond(LAST_EPOCH_SECOND - 300));
        String last = signed(with(with(VALID, "iat", LAST_EPOCH_SECOND - 600), "exp",
                LAST_EPOCH_SECOND));
        assertThat(atTheEnd.verifyAccess(last).issuedAt())
                .isEqualTo(Instant.ofEpochSecond(LAST_EPOCH_SECOND - 600));
    }

    @Test
    void thereIsNoClockLeewayOnExpiry() {
        String token = signed(VALID);

        assertRejected(() -> verifierAt(NOW.plusSeconds(601)).verifyAccess(token), TokenRejection.EXPIRED);
        assertRejected(() -> verifierAt(NOW.plusSeconds(600)).verifyAccess(token), TokenRejection.EXPIRED);
    }

    static Stream<Arguments> issueTimesAgainstTheVerificationInstant() {
        Instant at = NOW;
        return Stream.of(
                Arguments.of("iat 60 s ahead is accepted", at.plusSeconds(60), 600, null),
                Arguments.of("iat 61 s ahead is invalid", at.plusSeconds(61), 600,
                        TokenRejection.CLAIMS_INVALID),
                Arguments.of("iat 120 s ahead is invalid", at.plusSeconds(120), 600,
                        TokenRejection.CLAIMS_INVALID),
                Arguments.of("a lifetime of 601 s is invalid", at, 601, TokenRejection.CLAIMS_INVALID),
                Arguments.of("a lifetime of 599 s is invalid", at, 599, TokenRejection.CLAIMS_INVALID),
                Arguments.of("a lifetime of 300 s (a restricted token's) is invalid", at, 300,
                        TokenRejection.CLAIMS_INVALID),
                Arguments.of("a lifetime of 0 s is invalid", at, 0, TokenRejection.CLAIMS_INVALID),
                Arguments.of("a negative lifetime is invalid", at, -600, TokenRejection.CLAIMS_INVALID));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("issueTimesAgainstTheVerificationInstant")
    void theIssueTimeAndTheExactLifetimeAreEnforced(String name, Instant issuedAt, long lifetime,
            TokenRejection expected) {
        Map<String, Object> claims = with(with(VALID, "iat", issuedAt.getEpochSecond()), "exp",
                issuedAt.getEpochSecond() + lifetime);
        String token = signed(claims);

        if (expected == null) {
            assertThat(VERIFIER.verifyAccess(token).issuedAt()).isEqualTo(issuedAt);
        } else {
            assertRejected(() -> VERIFIER.verifyAccess(token), expected);
        }
    }

    @Test
    void anIssueTimeSixtySecondsAheadIsAcceptedWhenTheClockHasFractionalSeconds() {
        AccessTokenVerifier verifier = verifierAt(NOW.plusMillis(500));
        Map<String, Object> claims = with(with(VALID, "iat", NOW.getEpochSecond() + 60), "exp",
                NOW.getEpochSecond() + 660);

        assertThat(verifier.verifyAccess(signed(claims)).issuedAt()).isEqualTo(NOW.plusSeconds(60));
        assertRejected(() -> verifier.verifyAccess(signed(with(with(VALID, "iat",
                NOW.getEpochSecond() + 61), "exp", NOW.getEpochSecond() + 661))),
                TokenRejection.CLAIMS_INVALID);
    }

    @Test
    void anExpiredTokenIsExpiredOnlyWhenSignatureAndEveryClaimAreIntact() {
        AccessTokenVerifier late = verifierAt(Instant.parse("2026-10-12T10:30:00Z"));

        assertRejected(() -> late.verifyAccess(signed(VALID)), TokenRejection.EXPIRED);
        // I72: expired, but with a foreign signature, a portal audience or any other defect.
        assertRejected(() -> late.verifyAccess(signedBy(CURRENT_KID, FOREIGN.getPrivate(), VALID)),
                TokenRejection.BAD_SIGNATURE);
        assertRejected(() -> late.verifyAccess(signed(with(VALID, "aud", "confia-portal"))),
                TokenRejection.CLAIMS_INVALID);
        assertRejected(() -> late.verifyAccess(signed(with(VALID, "iss", "confia-portal"))),
                TokenRejection.CLAIMS_INVALID);
        assertRejected(() -> late.verifyAccess(signed(with(VALID, "permissions", List.of()))),
                TokenRejection.CLAIMS_INVALID);
        assertRejected(() -> late.verifyAccess(signed(without(VALID, "sid"))),
                TokenRejection.CLAIMS_INVALID);
        assertRejected(() -> late.verifyAccess(signed(with(VALID, "exp", NOW.getEpochSecond() + 601))),
                TokenRejection.CLAIMS_INVALID);
        assertRejected(() -> late.verifyAccess(signedBy(FOREIGN_KID, FOREIGN.getPrivate(), VALID)),
                TokenRejection.UNKNOWN_HEADER);
    }

    // ---------------------------------------------------------------------------------------------
    // Another identity domain (I68, I69, I70)
    // ---------------------------------------------------------------------------------------------

    @Test
    void aTokenOfThePortalSignedWithAKeyOutsideTheRingIsRejectedForItsUnknownKid() {
        Map<String, Object> portal = with(VALID, "aud", "confia-portal");

        assertAccessRejected(signedBy(FOREIGN_KID, FOREIGN.getPrivate(), portal),
                TokenRejection.UNKNOWN_HEADER);
    }

    @Test
    void aTokenWithTheAdministrativeKidButTheForeignKeyIsRejectedForItsSignature() {
        Map<String, Object> portal = with(VALID, "aud", "confia-portal");

        assertAccessRejected(signedBy(CURRENT_KID, FOREIGN.getPrivate(), portal),
                TokenRejection.BAD_SIGNATURE);
        assertAccessRejected(signedBy(CURRENT_KID, FOREIGN.getPrivate(), VALID),
                TokenRejection.BAD_SIGNATURE);
    }

    @Test
    void aTokenSignedWithTheAdministrativeKeyButWithThePortalAudienceIsRejectedForItsAudience() {
        assertAccessRejected(signed(with(VALID, "aud", "confia-portal")), TokenRejection.CLAIMS_INVALID);
    }

    // ---------------------------------------------------------------------------------------------
    // Garbage in (I73)
    // ---------------------------------------------------------------------------------------------

    static Stream<Arguments> garbage() {
        String valid = signed(VALID);
        String[] segments = valid.split("\\.");
        return Stream.of(
                Arguments.of("null", null),
                Arguments.of("the empty string", ""),
                Arguments.of("a blank string", "   "),
                Arguments.of("ten thousand A", "A".repeat(10_000)),
                Arguments.of("NUL bytes", "\0\0\0"),
                Arguments.of("NUL bytes inside a token", segments[0] + ".\0." + segments[2]),
                Arguments.of("a segment of megabytes",
                        segments[0] + "." + "A".repeat(3 * 1024 * 1024) + "." + segments[2]),
                Arguments.of("only dots", "..."),
                Arguments.of("Bearer prefixed", "Bearer " + valid),
                Arguments.of("a valid token with a trailing newline", valid + "\n"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("garbage")
    void garbageIsAlwaysATokenRejectionAndNeverAnotherException(String name, String input) {
        assertThatThrownBy(() -> VERIFIER.verifyAccess(input))
                .isExactlyInstanceOf(TokenRejectedException.class);
    }

    @Test
    void anAccessClaimSetInAnyMemberOrderIsTheSameToken() {
        // Member order is the issuer's choice and carries no meaning: the verifier reads by name.
        Map<String, Object> reversed = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(ACCESS_NAMES);
        Collections.reverse(names);
        names.forEach(name -> reversed.put(name, VALID.get(name)));

        assertThat(VERIFIER.verifyAccess(signed(reversed)).sessionId()).isEqualTo(SESSION);
    }

    // ---------------------------------------------------------------------------------------------
    // What a rejection carries
    // ---------------------------------------------------------------------------------------------

    @Test
    void aClaimsRejectionNamesOnlyItsReasonAndCarriesNeitherTheTokenNorAClaimValue() {
        String secretSubject = "SECRETO-SUBJECT";
        String token = signed(with(VALID, "sub", secretSubject));

        assertThatThrownBy(() -> VERIFIER.verifyAccess(token))
                .isExactlyInstanceOf(TokenRejectedException.class).satisfies(e -> {
                    assertThat(e.getMessage()).isEqualTo("CLAIMS_INVALID");
                    assertThat(e.getCause()).isNull();
                    assertThat(e.toString()).doesNotContain(secretSubject).doesNotContain(token)
                            .doesNotContain(TOKEN_ID.toString());
                });
    }

    private static void assertAccessRejected(String token, TokenRejection expected) {
        assertRejected(() -> VERIFIER.verifyAccess(token), expected);
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
