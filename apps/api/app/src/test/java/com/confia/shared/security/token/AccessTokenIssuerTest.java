package com.confia.shared.security.token;

import static com.confia.shared.security.token.AccessTokenFixtures.ACCOUNT;
import static com.confia.shared.security.token.AccessTokenFixtures.INSTITUTION;
import static com.confia.shared.security.token.AccessTokenFixtures.JWS;
import static com.confia.shared.security.token.AccessTokenFixtures.NOW;
import static com.confia.shared.security.token.AccessTokenFixtures.SESSION;
import static com.confia.shared.security.token.AccessTokenFixtures.headerTextOf;
import static com.confia.shared.security.token.AccessTokenFixtures.issuerAt;
import static com.confia.shared.security.token.AccessTokenFixtures.namesOf;
import static com.confia.shared.security.token.AccessTokenFixtures.payloadOf;
import static com.confia.shared.security.token.AccessTokenFixtures.payloadTextOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.AuthenticationMethod;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The issuer of the administrative access token (session-tokens-and-web-layer design.md, decision 4;
 * scenarios I58 to I62). Every test decodes what the issuer produced with the JDK's own base64
 * decoder and reads the claims as text, so that what is asserted is what a client would see on the
 * wire, not what the issuer believes it wrote.
 */
class AccessTokenIssuerTest {

    private static final Set<AuthenticationMethod> PASSWORD_ONLY = EnumSet.of(
            AuthenticationMethod.PASSWORD);
    private static final Set<AuthenticationMethod> PASSWORD_AND_OTP = EnumSet.of(
            AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD);

    private final AccessTokenIssuer issuer = issuerAt(NOW);

    // ---------------------------------------------------------------------------------------------
    // The access token: closed list, 600 seconds, audience (I58, I59)
    // ---------------------------------------------------------------------------------------------

    @Test
    void theClaimsOfAnAccessTokenAreExactlyTheNineOfTheDesignInTheirOrderWithNoSpaces() {
        AccessToken token = issuer.issueAccess(ACCOUNT, INSTITUTION, SESSION, PASSWORD_ONLY);

        JsonNode claims = payloadOf(token.compact());
        String jti = claims.get("jti").asString();
        assertThat(namesOf(claims)).containsExactly("iss", "aud", "sub", "exp", "iat", "jti", "sid",
                "tenant", "amr");
        assertThat(payloadTextOf(token.compact())).isEqualTo("{\"iss\":\"confia-admin\","
                + "\"aud\":\"confia-admin\",\"sub\":\"" + ACCOUNT + "\",\"exp\":"
                + (NOW.getEpochSecond() + 600) + ",\"iat\":" + NOW.getEpochSecond() + ",\"jti\":\""
                + jti + "\",\"sid\":\"" + SESSION + "\",\"tenant\":\"" + INSTITUTION
                + "\",\"amr\":[\"pwd\"]}");
        assertThat(claims.get("iss").isString()).isTrue();
        assertThat(claims.get("aud").isString()).as("aud is a string, not an array").isTrue();
        assertThat(claims.get("aud").asString()).isEqualTo("confia-admin");
    }

    @Test
    void anAccessTokenLivesExactlySixHundredSecondsAndReportsItsExpiry() {
        AccessToken token = issuer.issueAccess(ACCOUNT, INSTITUTION, SESSION, PASSWORD_ONLY);

        JsonNode claims = payloadOf(token.compact());
        assertThat(claims.get("iat").longValue()).isEqualTo(Instant.parse("2026-10-12T10:00:00Z")
                .getEpochSecond());
        assertThat(claims.get("exp").longValue()).isEqualTo(Instant.parse("2026-10-12T10:10:00Z")
                .getEpochSecond());
        assertThat(claims.get("exp").longValue() - claims.get("iat").longValue()).isEqualTo(600);
        assertThat(token.expiresAt()).isEqualTo(Instant.parse("2026-10-12T10:10:00Z"));
    }

    @Test
    void theIssueInstantIsTruncatedToWholeSecondsAndTheExpiryFollowsFromIt() {
        AccessTokenIssuer fractional = issuerAt(Instant.parse("2026-10-12T10:00:00.987654321Z"));

        AccessToken token = fractional.issueAccess(ACCOUNT, INSTITUTION, SESSION, PASSWORD_ONLY);

        JsonNode claims = payloadOf(token.compact());
        assertThat(claims.get("iat").longValue()).isEqualTo(NOW.getEpochSecond());
        assertThat(claims.get("exp").longValue()).isEqualTo(NOW.getEpochSecond() + 600);
        assertThat(token.expiresAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    void theClaimsCarryTheIdentifiersTheCallerGaveInTheirCanonicalForm() {
        UUID otherAccount = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

        JsonNode claims = payloadOf(
                issuer.issueAccess(otherAccount, INSTITUTION, SESSION, PASSWORD_ONLY).compact());

        assertThat(claims.get("sub").asString()).isEqualTo("00000000-0000-4000-8000-0000000000aa");
        assertThat(claims.get("tenant").asString()).isEqualTo(INSTITUTION.toString());
        assertThat(claims.get("sid").asString()).isEqualTo(SESSION.toString());
    }

    @Test
    void theHeaderIsTheCanonicalOneOfTheCurrentKeyAndTheTokenVerifiesWithTheRing() {
        AccessToken token = issuer.issueAccess(ACCOUNT, INSTITUTION, SESSION, PASSWORD_ONLY);

        assertThat(headerTextOf(token.compact())).isEqualTo("{\"alg\":\"EdDSA\",\"kid\":\""
                + JwsFixtures.CURRENT_KID + "\"}");
        assertThat(JWS.verify(token.compact()).kid()).isEqualTo(JwsFixtures.CURRENT_KID);
    }

    // ---------------------------------------------------------------------------------------------
    // amr (I61)
    // ---------------------------------------------------------------------------------------------

    @Test
    void amrIsPwdForAPasswordOnlySessionAndPwdThenOtpWhenTheSecondFactorWasCompleted() {
        JsonNode first = payloadOf(
                issuer.issueAccess(ACCOUNT, INSTITUTION, SESSION, PASSWORD_ONLY).compact());
        JsonNode second = payloadOf(
                issuer.issueAccess(ACCOUNT, INSTITUTION, SESSION, PASSWORD_AND_OTP).compact());

        assertThat(texts(first.get("amr"))).containsExactly("pwd");
        assertThat(texts(second.get("amr"))).containsExactly("pwd", "otp");
    }

    @Test
    void amrOrderDoesNotDependOnTheIterationOrderOfTheSetTheCallerPasses() {
        Set<AuthenticationMethod> reversed = new LinkedHashSet<>(
                List.of(AuthenticationMethod.ONE_TIME_PASSWORD, AuthenticationMethod.PASSWORD));
        Set<AuthenticationMethod> hashed = new HashSet<>(PASSWORD_AND_OTP);

        for (Set<AuthenticationMethod> methods : List.of(reversed, hashed, Set.of(
                AuthenticationMethod.ONE_TIME_PASSWORD, AuthenticationMethod.PASSWORD))) {
            JsonNode claims = payloadOf(
                    issuer.issueAccess(ACCOUNT, INSTITUTION, SESSION, methods).compact());

            assertThat(texts(claims.get("amr"))).containsExactly("pwd", "otp");
        }
    }

    @Test
    void aSessionWithoutThePasswordFactorCannotBeIssuedAToken() {
        assertThatThrownBy(() -> issuer.issueAccess(ACCOUNT, INSTITUTION, SESSION, Set.of()))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issuer.issueAccess(ACCOUNT, INSTITUTION, SESSION,
                EnumSet.of(AuthenticationMethod.ONE_TIME_PASSWORD)))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nothingIsIssuedForAMissingArgument() {
        assertThatThrownBy(() -> issuer.issueAccess(null, INSTITUTION, SESSION, PASSWORD_ONLY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> issuer.issueAccess(ACCOUNT, null, SESSION, PASSWORD_ONLY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> issuer.issueAccess(ACCOUNT, INSTITUTION, null, PASSWORD_ONLY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> issuer.issueAccess(ACCOUNT, INSTITUTION, SESSION, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---------------------------------------------------------------------------------------------
    // jti (I62) and personal data (I60)
    // ---------------------------------------------------------------------------------------------

    @Test
    void aThousandSuccessiveIssuesForTheSameSessionHaveAThousandDistinctJtis() {
        Set<String> jtis = new HashSet<>();

        for (int i = 0; i < 1000; i++) {
            JsonNode claims = payloadOf(
                    issuer.issueAccess(ACCOUNT, INSTITUTION, SESSION, PASSWORD_ONLY).compact());
            jtis.add(claims.get("jti").asString());
        }

        assertThat(jtis).hasSize(1000);
        assertThat(jtis).allSatisfy(jti -> assertThat(UUID.fromString(jti).toString()).isEqualTo(jti));
    }

    @Test
    void noPersonalDatumAppearsInTheHeaderOrThePayloadAndThereIsNoPermissionsClaim() {
        String email = "maria.lopez@colegio.edu.hn";
        String fullName = "Maria Lopez";
        String identityDocument = "0801-1990-12345";
        AccessToken token = issuer.issueAccess(ACCOUNT, INSTITUTION, SESSION, PASSWORD_AND_OTP);

        String everything = headerTextOf(token.compact()) + payloadTextOf(token.compact());

        assertThat(everything).doesNotContain(email).doesNotContain("maria").doesNotContain("lopez")
                .doesNotContain(fullName).doesNotContain(identityDocument)
                .doesNotContain("@").doesNotContain("permissions");
        assertThat(payloadOf(token.compact()).get("sub").asString()).isEqualTo(ACCOUNT.toString());
    }

    // ---------------------------------------------------------------------------------------------
    // What a token prints
    // ---------------------------------------------------------------------------------------------

    @Test
    void anAccessTokenPrintsNeitherItsTokenNorAnyPieceOfIt() {
        AccessToken token = issuer.issueAccess(ACCOUNT, INSTITUTION, SESSION, PASSWORD_ONLY);
        String[] segments = token.compact().split("\\.");

        String printed = token.toString();

        assertThat(printed).doesNotContain(token.compact()).doesNotContain(segments[0])
                .doesNotContain(segments[1]).doesNotContain(segments[2])
                .doesNotContain(ACCOUNT.toString()).contains("redacted")
                .contains(NOW.plusSeconds(600).toString());
        assertThat(String.valueOf(token)).isEqualTo(printed);
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.asString()));
        return values;
    }
}
