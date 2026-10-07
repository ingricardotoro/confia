package com.confia.shared.security.token;

import static com.confia.shared.security.token.JwsFixtures.CURRENT_KID;
import static com.confia.shared.security.token.JwsFixtures.b64;
import static com.confia.shared.security.token.JwsFixtures.canonicalHeaderJson;
import static com.confia.shared.security.token.JwsFixtures.newPair;
import static com.confia.shared.security.token.JwsFixtures.tokenOverSegments;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The five follow-ups of the independent review of the codec (session-tokens-and-web-layer tasks.md,
 * dated note of 2026-10-06, S1 to S4; S5 is in {@link CompactJwsPropertiesTest}). They harden the
 * codec before the first claim reader is built on top of it: what {@code sign} accepts, what a
 * verified token lets the caller mutate, how deep and how large a hostile input may be, and which
 * bytes count as a payload.
 */
class CompactJwsHardeningTest {

    private static final KeyPair CURRENT = newPair();
    private static final CompactJws JWS = new CompactJws(SigningKeyRing.of(
            SigningKey.signing(CURRENT_KID, CURRENT.getPublic(), CURRENT.getPrivate())));
    private static final String HEADER_SEGMENT = b64(canonicalHeaderJson(CURRENT_KID));

    // ---------------------------------------------------------------------------------------------
    // S1: sign accepts a JSON object and a token the verifier would accept
    // ---------------------------------------------------------------------------------------------

    static Stream<Arguments> payloadsThatAreNotObjects() {
        return Stream.of(
                Arguments.of("an array", bytes("[1,2]")),
                Arguments.of("a number", bytes("123")),
                Arguments.of("a string", bytes("\"sub\"")),
                Arguments.of("the literal null", bytes("null")),
                Arguments.of("not JSON", bytes("not json")),
                Arguments.of("an empty payload", new byte[0]),
                Arguments.of("two objects in a row", bytes("{\"a\":1}{\"b\":2}")),
                Arguments.of("an object with a repeated name", bytes("{\"a\":1,\"a\":2}")),
                Arguments.of("an object followed by garbage", bytes("{\"a\":1} x")),
                Arguments.of("an object with a byte order mark", bytes("﻿{\"a\":1}")));
    }

    @ParameterizedTest(name = "[{index}] sign refuses {0}")
    @MethodSource("payloadsThatAreNotObjects")
    void signRefusesAPayloadThatIsNotAJsonObjectWithUniqueNames(String name, byte[] payload) {
        assertThatThrownBy(() -> JWS.sign(payload)).isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signRefusesANullPayload() {
        assertThatThrownBy(() -> JWS.sign(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void signRefusesAPayloadThatWouldYieldATokenOfMoreThan2048Characters() {
        byte[] small = bytes("{\"a\":\"x\"}");
        byte[] huge = bytes("{\"a\":\"" + "x".repeat(3000) + "\"}");

        assertThat(JWS.verify(JWS.sign(small)).claims().get("a").asString()).isEqualTo("x");
        assertThatThrownBy(() -> JWS.sign(huge)).isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("xxxx");
    }

    @Test
    void signAcceptsATokenOfExactly2048CharactersAndRefusesOneOf2049() {
        for (int kidLength = 1; kidLength <= 64; kidLength++) {
            String kid = "k".repeat(kidLength);
            KeyPair pair = newPair();
            CompactJws jws = new CompactJws(
                    SigningKeyRing.of(SigningKey.signing(kid, pair.getPublic(), pair.getPrivate())));
            int fixed = b64(canonicalHeaderJson(kid)).length() + 2 + 86;
            int segmentAtLimit = 2048 - fixed;
            if (segmentAtLimit % 4 == 1 || (segmentAtLimit + 1) % 4 == 1) {
                continue;
            }
            byte[] atLimit = objectWithSegmentLength(segmentAtLimit);
            byte[] overLimit = objectWithSegmentLength(segmentAtLimit + 1);

            String token = jws.sign(atLimit);

            assertThat(token).hasSize(2048);
            assertThat(jws.verify(token).kid()).isEqualTo(kid);
            assertThatThrownBy(() -> jws.sign(overLimit))
                    .isExactlyInstanceOf(IllegalArgumentException.class);
            return;
        }
        throw new AssertionError("no kid length reaches both boundary lengths");
    }

    // ---------------------------------------------------------------------------------------------
    // S2: a verified token hands out no mutable tree
    // ---------------------------------------------------------------------------------------------

    @Test
    void mutatingTheClaimsAVerifiedTokenReturnsNeverChangesWhatItReturnsNext() {
        VerifiedJws verified = JWS.verify(JWS.sign(bytes("{\"sub\":\"a\",\"nested\":{\"n\":1}}")));

        JsonNode first = verified.claims();
        if (first instanceof ObjectNode mutable) {
            mutable.put("injected", "x");
            mutable.remove("sub");
            ((ObjectNode) mutable.get("nested")).put("n", 2);
        }
        JsonNode second = verified.claims();

        assertThat(second.has("injected")).isFalse();
        assertThat(second.get("sub").asString()).isEqualTo("a");
        assertThat(second.get("nested").get("n").asInt()).isEqualTo(1);
    }

    @Test
    void aVerifiedTokenPrintsItsKidAndNothingOfItsPayload() {
        VerifiedJws verified = JWS.verify(JWS.sign(bytes("{\"secret\":\"SECRETO-DE-PRUEBA\"}")));

        assertThat(verified.toString()).isEqualTo("VerifiedJws[kid=" + CURRENT_KID + "]");
    }

    // ---------------------------------------------------------------------------------------------
    // S3: a hostile depth and a hostile size
    // ---------------------------------------------------------------------------------------------

    @Test
    void aSignedPayloadNestedDeeperThan500LevelsIsMalformedClaims() {
        String deep = "{\"a\":" + "[".repeat(600) + "]".repeat(600) + "}";

        assertThat(deep.length()).isLessThan(1300);
        assertRejected(tokenOverSegments(HEADER_SEGMENT, b64(deep), CURRENT.getPrivate()),
                TokenRejection.MALFORMED_CLAIMS);
    }

    @Test
    void aSignedPayloadNestedWithinTheLimitIsAccepted() {
        String shallow = "{\"a\":" + "[".repeat(100) + "]".repeat(100) + "}";

        assertThat(JWS.verify(tokenOverSegments(HEADER_SEGMENT, b64(shallow), CURRENT.getPrivate()))
                .claims().has("a")).isTrue();
    }

    @Test
    void aTokenOfTenMegabytesIsMalformedWithoutAnyOfItBeingDecoded() {
        String payloadSegment = "A".repeat(10 * 1024 * 1024);

        assertRejected(HEADER_SEGMENT + "." + payloadSegment + "." + b64(new byte[64]),
                TokenRejection.MALFORMED);
        assertRejected("A".repeat(10 * 1024 * 1024) + ".A.A", TokenRejection.MALFORMED);
    }

    // ---------------------------------------------------------------------------------------------
    // S4: the payload is strict UTF-8, never an encoding Jackson would detect by itself
    // ---------------------------------------------------------------------------------------------

    static Stream<Arguments> payloadsThatAreNotStrictUtf8() {
        byte[] bom = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
        return Stream.of(
                Arguments.of("UTF-16 little endian", "{\"a\":1}".getBytes(StandardCharsets.UTF_16LE)),
                Arguments.of("UTF-16 big endian", "{\"a\":1}".getBytes(StandardCharsets.UTF_16BE)),
                Arguments.of("UTF-32 big endian", "{\"a\":1}".getBytes(java.nio.charset.Charset
                        .forName("UTF-32BE"))),
                Arguments.of("UTF-16 with a byte order mark",
                        "{\"a\":1}".getBytes(StandardCharsets.UTF_16)),
                Arguments.of("UTF-8 with a byte order mark", concat(bom, bytes("{\"a\":1}"))),
                Arguments.of("an overlong slash inside a string",
                        concat(bytes("{\"a\":\""), new byte[] {(byte) 0xc0, (byte) 0xaf},
                                bytes("\"}"))),
                Arguments.of("an encoded surrogate inside a string",
                        concat(bytes("{\"a\":\""), new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                                bytes("\"}"))),
                Arguments.of("a truncated sequence inside a string",
                        concat(bytes("{\"a\":\""), new byte[] {(byte) 0xe2, (byte) 0x82},
                                bytes("\"}"))));
    }

    @ParameterizedTest(name = "[{index}] payload: {0}")
    @MethodSource("payloadsThatAreNotStrictUtf8")
    void aValidlySignedPayloadThatIsNotStrictUtf8IsMalformedClaims(String name, byte[] payload) {
        assertRejected(tokenOverSegments(HEADER_SEGMENT, b64(payload), CURRENT.getPrivate()),
                TokenRejection.MALFORMED_CLAIMS);
    }

    @Test
    void aPayloadWithMultiByteUtf8CharactersIsAcceptedAndReadBack() {
        String text = "café € 😀";
        String token = tokenOverSegments(HEADER_SEGMENT, b64("{\"a\":\"" + text + "\"}"),
                CURRENT.getPrivate());

        assertThat(JWS.verify(token).claims().get("a").asString()).isEqualTo(text);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static void assertRejected(String token, TokenRejection expected) {
        assertThatThrownBy(() -> JWS.verify(token)).isExactlyInstanceOf(TokenRejectedException.class)
                .satisfies(e -> assertThat(((TokenRejectedException) e).rejection())
                        .isEqualTo(expected));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    /** A JSON object whose base64url segment has exactly the given length. */
    private static byte[] objectWithSegmentLength(int segmentLength) {
        int bytesLength = switch (segmentLength % 4) {
            case 0 -> segmentLength / 4 * 3;
            case 2 -> segmentLength / 4 * 3 + 1;
            case 3 -> segmentLength / 4 * 3 + 2;
            default -> throw new IllegalArgumentException("no byte length encodes to that segment");
        };
        String frame = "{\"p\":\"\"}";
        byte[] object = bytes("{\"p\":\"" + "x".repeat(bytesLength - frame.length()) + "\"}");
        assertThat(b64(object)).hasSize(segmentLength);
        return object;
    }
}
