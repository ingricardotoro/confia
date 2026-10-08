package com.confia.shared.security.token;

import static com.confia.shared.security.token.AccessTokenFixtures.JWS;
import static com.confia.shared.security.token.AccessTokenFixtures.accessClaims;
import static com.confia.shared.security.token.AccessTokenFixtures.clockAt;
import static com.confia.shared.security.token.AccessTokenFixtures.payloadOf;
import static com.confia.shared.security.token.AccessTokenFixtures.signed;
import static com.confia.shared.security.token.AccessTokenFixtures.verifierAt;
import static com.confia.shared.security.token.AccessTokenFixtures.with;
import static com.confia.shared.security.token.AccessTokenFixtures.without;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import java.time.Instant;
import java.util.EnumSet;
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
 * The claims of the administrative access token over random identifiers, instants and edits
 * (session-tokens-and-web-layer design.md, decision 4; scenarios I58 to I63 and I139): whatever the
 * account, institution, session and issue instant, what the issuer signs the verifier returns
 * unchanged, the lifetime is always exactly 600 seconds, a token is live for exactly the window the
 * rules draw, and no edit of the claim list (one claim removed, one extra added) is ever accepted.
 */
class AccessTokenClaimsPropertyTest {

    private static final long EARLIEST_ISSUE = 946_684_800L; // 2000-01-01T00:00:00Z
    private static final long LATEST_ISSUE = 4_102_444_800L; // 2100-01-01T00:00:00Z

    @Property(tries = 300)
    void whatTheIssuerSignsTheVerifierReturnsForAnyIdentifiersAndAnyInstant(
            @ForAll("uuids") UUID account, @ForAll("uuids") UUID institution, @ForAll("uuids") UUID session,
            @ForAll("methods") Set<AuthenticationMethod> methods,
            @ForAll @LongRange(min = 946_684_800L, max = 4_102_444_800L) long epochSecond,
            @ForAll @IntRange(min = 0, max = 999_999_999) int nanos) {
        Instant at = Instant.ofEpochSecond(epochSecond, nanos);
        AccessToken token = new AccessTokenIssuer(JWS, clockAt(at)).issueAccess(account, institution,
                session, methods);

        AuthenticatedActor actor = verifierAt(Instant.ofEpochSecond(epochSecond)).verifyAccess(
                token.compact());

        assertThat(actor.accountId()).isEqualTo(account);
        assertThat(actor.institutionId()).isEqualTo(institution);
        assertThat(actor.sessionId()).isEqualTo(session);
        assertThat(actor.methods()).isEqualTo(methods);
        assertThat(actor.issuedAt()).isEqualTo(Instant.ofEpochSecond(epochSecond));
        assertThat(actor.expiresAt()).isEqualTo(Instant.ofEpochSecond(epochSecond + 600));
        assertThat(token.expiresAt()).isEqualTo(actor.expiresAt());
        assertThat(payloadOf(token.compact()).get("exp").longValue()
                - payloadOf(token.compact()).get("iat").longValue()).isEqualTo(600);
    }

    /**
     * A token issued at T is rejected as an issue time too far ahead before T - 60 s, accepted from
     * T - 60 s up to and including T + 599 s, and expired from T + 600 s on.
     */
    @Property(tries = 400)
    void aTokenIsLiveForExactlyTheWindowTheTimeRulesDraw(
            @ForAll @LongRange(min = EARLIEST_ISSUE, max = LATEST_ISSUE) long epochSecond,
            @ForAll @IntRange(min = -300, max = 900) int offsetSeconds) {
        Instant issued = Instant.ofEpochSecond(epochSecond);
        String token = signed(accessClaims(issued));
        AccessTokenVerifier verifier = verifierAt(issued.plusSeconds(offsetSeconds));

        if (offsetSeconds < -60) {
            assertThatThrownBy(() -> verifier.verifyAccess(token))
                    .isExactlyInstanceOf(TokenRejectedException.class)
                    .satisfies(e -> assertThat(((TokenRejectedException) e).rejection())
                            .isEqualTo(TokenRejection.CLAIMS_INVALID));
        } else if (offsetSeconds < 600) {
            assertThat(verifier.verifyAccess(token).issuedAt()).isEqualTo(issued);
        } else {
            assertThatThrownBy(() -> verifier.verifyAccess(token))
                    .isExactlyInstanceOf(TokenRejectedException.class)
                    .satisfies(e -> assertThat(((TokenRejectedException) e).rejection())
                            .isEqualTo(TokenRejection.EXPIRED));
        }
    }

    @Property(tries = 300)
    void anyClaimAddedToTheClosedListMakesTheTokenInvalid(
            @ForAll("extraClaimNames") String extra, @ForAll("claimValues") Object value) {
        Instant now = Instant.parse("2026-10-12T10:00:00Z");

        assertInvalid(() -> verifierAt(now).verifyAccess(signed(with(accessClaims(now), extra, value))));
    }

    @Property(tries = 100)
    void anyDeclaredClaimRemovedMakesTheTokenInvalid(@ForAll @IntRange(min = 0, max = 8) int index) {
        Instant now = Instant.parse("2026-10-12T10:00:00Z");
        String access = List.copyOf(accessClaims(now).keySet()).get(index);

        assertInvalid(() -> verifierAt(now).verifyAccess(signed(without(accessClaims(now), access))));
    }

    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.longs().tuple2().map(bits -> new UUID(bits.get1(), bits.get2()));
    }

    @Provide
    Arbitrary<Set<AuthenticationMethod>> methods() {
        return Arbitraries.of(
                (Set<AuthenticationMethod>) EnumSet.of(AuthenticationMethod.PASSWORD),
                EnumSet.of(AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD));
    }

    @Provide
    Arbitrary<String> extraClaimNames() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10)
                .filter(name -> !Set.of("iss", "aud", "sub", "exp", "iat", "jti", "sid", "tenant",
                        "amr").contains(name));
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
