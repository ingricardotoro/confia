package com.confia.shared.security.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import tools.jackson.databind.json.JsonMapper;

/**
 * The codec over random payloads (session-tokens-and-web-layer design.md, decision 2; scenarios I47
 * and I55): whatever the payload, the issued token verifies and returns what was signed, and a token
 * with any single bit of any character changed is rejected with a {@link TokenRejectedException} and
 * never with another kind of exception. Every character of the token is covered by the signature, by
 * the canonical header or by the canonical encoding, so no bit is free.
 */
class CompactJwsPropertiesTest {

    private static final KeyPair PAIR = JwsFixtures.newPair();
    private static final CompactJws JWS = new CompactJws(SigningKeyRing.of(
            SigningKey.signing(JwsFixtures.CURRENT_KID, PAIR.getPublic(), PAIR.getPrivate())));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Property(tries = 200)
    void anIssuedTokenVerifiesAndReturnsExactlyTheClaimsThatWereSigned(
            @ForAll("claims") Map<String, String> claims) {
        String token = JWS.sign(JSON.writeValueAsBytes(claims));

        VerifiedJws verified = JWS.verify(token);

        assertThat(verified.kid()).isEqualTo(JwsFixtures.CURRENT_KID);
        assertThat(verified.claims().size()).isEqualTo(claims.size());
        claims.forEach((name, value) -> assertThat(verified.claims().get(name).asString())
                .isEqualTo(value));
    }

    @Property(tries = 600)
    void aTokenWithAnySingleBitChangedIsRejectedAsATokenRejection(
            @ForAll("claims") Map<String, String> claims,
            @ForAll @IntRange(min = 0, max = 4095) int position,
            @ForAll @IntRange(min = 0, max = 7) int bit) {
        String token = JWS.sign(JSON.writeValueAsBytes(claims));
        byte[] bytes = token.getBytes(StandardCharsets.ISO_8859_1);
        bytes[position % bytes.length] ^= (byte) (1 << bit);
        String altered = new String(bytes, StandardCharsets.ISO_8859_1);

        assertThat(altered).isNotEqualTo(token);
        assertThatThrownBy(() -> JWS.verify(altered)).isExactlyInstanceOf(TokenRejectedException.class);
    }

    @Provide
    Arbitrary<Map<String, String>> claims() {
        Arbitrary<String> names = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8);
        Arbitrary<String> values = Arbitraries.strings().ascii().ofMinLength(0).ofMaxLength(60);
        return Arbitraries.maps(names, values).ofMinSize(0).ofMaxSize(5);
    }
}
