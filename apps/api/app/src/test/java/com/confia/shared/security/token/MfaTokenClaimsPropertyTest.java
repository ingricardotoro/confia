package com.confia.shared.security.token;

import static com.confia.shared.security.token.AccessTokenFixtures.JWS;
import static com.confia.shared.security.token.AccessTokenFixtures.clockAt;
import static com.confia.shared.security.token.AccessTokenFixtures.mfaClaims;
import static com.confia.shared.security.token.AccessTokenFixtures.payloadOf;
import static com.confia.shared.security.token.AccessTokenFixtures.signed;
import static com.confia.shared.security.token.AccessTokenFixtures.verifierAt;
import static com.confia.shared.security.token.AccessTokenFixtures.with;
import static com.confia.shared.security.token.AccessTokenFixtures.without;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * The claims of the restricted MFA token over random identifiers, instants and edits
 * (session-tokens-and-web-layer design.md, decision 4; scenario I74): whatever the account,
 * institution, purpose and issue instant, what the issuer signs the verifier returns unchanged, the
 * lifetime is always exactly 300 seconds, there is never a {@code sid}, and no edit of the claim list
 * (one claim removed, one extra added) is ever accepted.
 */
class MfaTokenClaimsPropertyTest {

    @Property(tries = 300)
    void aRestrictedTokenRoundTripsWithExactlyThreeHundredSecondsForAnyIdentifiers(
            @ForAll("uuids") UUID account, @ForAll("uuids") UUID institution,
            @ForAll MfaPurpose purpose,
            @ForAll @LongRange(min = 946_684_800L, max = 4_102_444_800L) long epochSecond) {
        Instant at = Instant.ofEpochSecond(epochSecond);
        AccessToken token = new AccessTokenIssuer(JWS, clockAt(at)).issueMfa(account, institution,
                purpose);

        MfaTokenClaims claims = verifierAt(at).verifyMfa(token.compact());

        assertThat(claims.accountId()).isEqualTo(account);
        assertThat(claims.institutionId()).isEqualTo(institution);
        assertThat(claims.purpose()).isEqualTo(purpose);
        assertThat(claims.expiresAt()).isEqualTo(at.plusSeconds(300));
        assertThat(payloadOf(token.compact()).has("sid")).isFalse();
    }

    @Property(tries = 300)
    void anyClaimAddedToTheClosedListMakesTheRestrictedTokenInvalid(
            @ForAll("extraClaimNames") String extra, @ForAll("claimValues") Object value) {
        Instant now = Instant.parse("2026-10-12T10:00:00Z");

        assertInvalid(() -> verifierAt(now).verifyMfa(signed(with(mfaClaims(now, "mfa-verify"), extra,
                value))));
    }

    @Property(tries = 100)
    void anyDeclaredClaimRemovedMakesTheRestrictedTokenInvalid(
            @ForAll @IntRange(min = 0, max = 8) int index) {
        Instant now = Instant.parse("2026-10-12T10:00:00Z");
        String name = List.copyOf(mfaClaims(now, "mfa-verify").keySet()).get(index);

        assertInvalid(() -> verifierAt(now).verifyMfa(
                signed(without(mfaClaims(now, "mfa-verify"), name))));
    }

    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.longs().tuple2().map(bits -> new UUID(bits.get1(), bits.get2()));
    }

    @Provide
    Arbitrary<String> extraClaimNames() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10)
                .filter(name -> !Set.of("iss", "aud", "sub", "exp", "iat", "jti", "tenant", "amr",
                        "purpose").contains(name));
    }

    @Provide
    Arbitrary<Object> claimValues() {
        return Arbitraries.oneOf(
                Arbitraries.strings().ascii().ofMaxLength(20).map(value -> (Object) value),
                Arbitraries.integers().map(value -> (Object) value),
                Arbitraries.just((Object) List.of()),
                Arbitraries.just((Object) Map.of()),
                Arbitraries.just((Object) true));
    }

    private static void assertInvalid(Runnable verification) {
        assertThatThrownBy(verification::run).isExactlyInstanceOf(TokenRejectedException.class)
                .satisfies(e -> assertThat(((TokenRejectedException) e).rejection())
                        .isEqualTo(TokenRejection.CLAIMS_INVALID));
    }
}
