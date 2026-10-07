package com.confia.shared.security.token;

import static com.confia.shared.security.token.AccessTokenFixtures.ACCOUNT;
import static com.confia.shared.security.token.AccessTokenFixtures.INSTITUTION;
import static com.confia.shared.security.token.AccessTokenFixtures.NOW;
import static com.confia.shared.security.token.AccessTokenFixtures.headerTextOf;
import static com.confia.shared.security.token.AccessTokenFixtures.issuerAt;
import static com.confia.shared.security.token.AccessTokenFixtures.namesOf;
import static com.confia.shared.security.token.AccessTokenFixtures.payloadOf;
import static com.confia.shared.security.token.AccessTokenFixtures.payloadTextOf;
import static com.confia.shared.security.token.AccessTokenFixtures.verifierAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The issuer of the restricted MFA token (session-tokens-and-web-layer design.md, decision 4;
 * scenarios I74 and I75). As in {@link AccessTokenIssuerTest}, every test decodes what the issuer
 * produced with the JDK's own base64 decoder, so that what is asserted is what a client would see.
 */
class MfaTokenIssuerTest {

    private final AccessTokenIssuer issuer = issuerAt(NOW);

    @ParameterizedTest
    @EnumSource(MfaPurpose.class)
    void theRestrictedTokenHasTheNineClaimsOfItsKindThreeHundredSecondsAndNoSid(MfaPurpose purpose) {
        AccessToken token = issuer.issueMfa(ACCOUNT, INSTITUTION, purpose);

        JsonNode claims = payloadOf(token.compact());
        String jti = claims.get("jti").asString();
        assertThat(namesOf(claims)).containsExactly("iss", "aud", "sub", "exp", "iat", "jti",
                "tenant", "amr", "purpose");
        assertThat(claims.has("sid")).isFalse();
        assertThat(payloadTextOf(token.compact())).isEqualTo("{\"iss\":\"confia-admin\","
                + "\"aud\":\"confia-admin-mfa\",\"sub\":\"" + ACCOUNT + "\",\"exp\":"
                + (NOW.getEpochSecond() + 300) + ",\"iat\":" + NOW.getEpochSecond() + ",\"jti\":\""
                + jti + "\",\"tenant\":\"" + INSTITUTION + "\",\"amr\":[\"pwd\"],\"purpose\":\""
                + purpose.claimValue() + "\"}");
        assertThat(claims.get("exp").longValue() - claims.get("iat").longValue()).isEqualTo(300);
        assertThat(token.expiresAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void thePurposesAreExactlyMfaVerifyAndMfaEnroll() {
        assertThat(Arrays.stream(MfaPurpose.values()).map(MfaPurpose::claimValue))
                .containsExactly("mfa-verify", "mfa-enroll");
        assertThat(MfaPurpose.fromClaimValue("mfa-verify")).hasValue(MfaPurpose.MFA_VERIFY);
        assertThat(MfaPurpose.fromClaimValue("mfa-enroll")).hasValue(MfaPurpose.MFA_ENROLL);
        assertThat(MfaPurpose.fromClaimValue("admin")).isEmpty();
        assertThat(MfaPurpose.fromClaimValue("MFA-VERIFY")).isEmpty();
        assertThat(MfaPurpose.fromClaimValue("")).isEmpty();
    }

    @Test
    void theRestrictedTokenHasADistinctJtiOnEveryIssue() {
        Set<String> jtis = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            jtis.add(payloadOf(issuer.issueMfa(ACCOUNT, INSTITUTION, MfaPurpose.MFA_VERIFY)
                    .compact()).get("jti").asString());
        }

        assertThat(jtis).hasSize(200);
    }

    @Test
    void theIssueInstantIsTruncatedToWholeSecondsAndTheExpiryFollowsFromIt() {
        AccessTokenIssuer fractional = issuerAt(NOW.plusNanos(987_654_321));

        AccessToken token = fractional.issueMfa(ACCOUNT, INSTITUTION, MfaPurpose.MFA_VERIFY);

        JsonNode claims = payloadOf(token.compact());
        assertThat(claims.get("iat").longValue()).isEqualTo(NOW.getEpochSecond());
        assertThat(claims.get("exp").longValue()).isEqualTo(NOW.getEpochSecond() + 300);
        assertThat(token.expiresAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void noPersonalDatumAppearsInTheHeaderOrThePayload() {
        AccessToken token = issuer.issueMfa(ACCOUNT, INSTITUTION, MfaPurpose.MFA_ENROLL);

        String everything = headerTextOf(token.compact()) + payloadTextOf(token.compact());

        assertThat(everything).doesNotContain("maria").doesNotContain("lopez").doesNotContain("@")
                .doesNotContain("permissions");
        assertThat(payloadOf(token.compact()).get("sub").asString()).isEqualTo(ACCOUNT.toString());
    }

    @Test
    void nothingIsIssuedForAMissingArgument() {
        assertThatThrownBy(() -> issuer.issueMfa(null, INSTITUTION, MfaPurpose.MFA_VERIFY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> issuer.issueMfa(ACCOUNT, null, MfaPurpose.MFA_VERIFY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> issuer.issueMfa(ACCOUNT, INSTITUTION, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Issuing a restricted token writes to no table (I75): the issuer holds nothing but the codec and a
     * clock, so there is no repository, data source or transaction it could reach. A field of any
     * other type would be a new way to write, and fails here until someone justifies it.
     */
    @Test
    void theIssuerHoldsNothingButTheCodecAndAClockSoItCanWriteToNoTable() {
        List<Class<?>> fieldTypes = new ArrayList<>();
        for (Field field : AccessTokenIssuer.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                fieldTypes.add(field.getType());
            }
        }

        assertThat(fieldTypes).containsExactlyInAnyOrder(CompactJws.class, Clock.class);
        assertThat(AccessTokenIssuer.class.getConstructors()).hasSize(1);
        assertThat(AccessTokenIssuer.class.getConstructors()[0].getParameterTypes())
                .containsExactly(CompactJws.class, Clock.class);
    }

    @Test
    void whatTheIssuerIssuesTheMfaVerifierAcceptsAtTheSameInstant() {
        AccessToken mfa = issuer.issueMfa(ACCOUNT, INSTITUTION, MfaPurpose.MFA_ENROLL);

        MfaTokenClaims claims = verifierAt(NOW).verifyMfa(mfa.compact());

        assertThat(claims.accountId()).isEqualTo(ACCOUNT);
        assertThat(claims.institutionId()).isEqualTo(INSTITUTION);
        assertThat(claims.purpose()).isEqualTo(MfaPurpose.MFA_ENROLL);
        assertThat(claims.issuedAt()).isEqualTo(NOW);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void theClaimsPrintTheirIdentifiersAndNeverTheTokenIdentifier() {
        AccessToken mfa = issuer.issueMfa(ACCOUNT, INSTITUTION, MfaPurpose.MFA_VERIFY);
        MfaTokenClaims claims = verifierAt(NOW).verifyMfa(mfa.compact());

        String printed = claims.toString();

        assertThat(printed).contains(ACCOUNT.toString()).contains(INSTITUTION.toString())
                .contains("MFA_VERIFY").doesNotContain(claims.tokenId().toString());
    }
}
