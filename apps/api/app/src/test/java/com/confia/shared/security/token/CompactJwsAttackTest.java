package com.confia.shared.security.token;

import static com.confia.shared.security.token.JwsFixtures.CURRENT_KID;
import static com.confia.shared.security.token.JwsFixtures.PREVIOUS_KID;
import static com.confia.shared.security.token.JwsFixtures.b64;
import static com.confia.shared.security.token.JwsFixtures.canonicalHeaderJson;
import static com.confia.shared.security.token.JwsFixtures.issuedToken;
import static com.confia.shared.security.token.JwsFixtures.jdkSign;
import static com.confia.shared.security.token.JwsFixtures.malleableVersionOf;
import static com.confia.shared.security.token.JwsFixtures.newPair;
import static com.confia.shared.security.token.JwsFixtures.signatureOf;
import static com.confia.shared.security.token.JwsFixtures.token;
import static com.confia.shared.security.token.JwsFixtures.tokenOverSegments;
import static com.confia.shared.security.token.JwsFixtures.withSignature;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.token.JwsFixtures.RfcVector;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The attacks on the compact JWS codec, written before the codec (session-tokens-and-web-layer
 * design.md, decision 2; scenarios I47 to I57, I138 and I147). Every case builds the token with the
 * JDK's own signer and hand-written text, so each is a token an attacker could really produce: most
 * carry a VALID signature of the system's own key over the forged header or the forged segment, which
 * is exactly why the header must be compared with the canonical one before anything else is believed.
 *
 * <p>Each group asserts the exact {@link TokenRejection}, in the order of the six verification
 * steps, so that a reordered or weakened step shows up as a different reason.
 */
class CompactJwsAttackTest {

    private static final KeyPair CURRENT = newPair();
    private static final KeyPair PREVIOUS = newPair();
    private static final KeyPair FOREIGN = newPair();

    private static final SigningKeyRing RING = SigningKeyRing.of(
            SigningKey.signing(CURRENT_KID, CURRENT.getPublic(), CURRENT.getPrivate()),
            SigningKey.verifyOnly(PREVIOUS_KID, PREVIOUS.getPublic()));
    private static final CompactJws JWS = new CompactJws(RING);

    private static final String BASE64URL_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final String PAYLOAD = "{\"sub\":\"7d444840-9dc0-11d1-b245-5ffdce74fad2\",\"n\":1}";
    private static final String HEADER_SEGMENT = b64(canonicalHeaderJson(CURRENT_KID));

    // ---------------------------------------------------------------------------------------------
    // The accepted token (I47)
    // ---------------------------------------------------------------------------------------------

    @Test
    void anIssuedTokenVerifiesAndItsHeaderHoldsExactlyAlgAndKid() {
        String token = JWS.sign(PAYLOAD.getBytes(StandardCharsets.UTF_8));

        String headerText = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]),
                StandardCharsets.UTF_8);
        VerifiedJws verified = JWS.verify(token);

        assertThat(headerText).isEqualTo("{\"alg\":\"EdDSA\",\"kid\":\"admin-2026a\"}");
        assertThat(verified.kid()).isEqualTo(CURRENT_KID);
        assertThat(verified.claims().get("sub").asString())
                .isEqualTo("7d444840-9dc0-11d1-b245-5ffdce74fad2");
        assertThat(verified.claims().get("n").asInt()).isEqualTo(1);
        assertThat(token).hasSize(HEADER_SEGMENT.length() + 1 + b64(PAYLOAD).length() + 1 + 86);
    }

    @Test
    void aTokenSignedByTheKeyOfTheRingThatOnlyVerifiesIsAccepted() {
        SigningKeyRing otherRing = SigningKeyRing.of(
                SigningKey.signing(PREVIOUS_KID, PREVIOUS.getPublic(), PREVIOUS.getPrivate()),
                SigningKey.verifyOnly(CURRENT_KID, CURRENT.getPublic()));
        String token = new CompactJws(otherRing).sign(PAYLOAD.getBytes(StandardCharsets.UTF_8));

        VerifiedJws verified = JWS.verify(token);

        assertThat(verified.kid()).isEqualTo(PREVIOUS_KID);
        assertThat(verified.claims().get("n").asInt()).isEqualTo(1);
    }

    @Test
    void thePayloadIsReturnedEvenWhenItIsTheEmptyObject() {
        VerifiedJws verified = JWS.verify(issuedToken(CURRENT_KID, "{}", CURRENT.getPrivate()));

        assertThat(verified.claims().isObject()).isTrue();
        assertThat(verified.claims().size()).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // Step 2: the header is the canonical one of a known kid, byte for byte (I48 to I52, I147)
    // ---------------------------------------------------------------------------------------------

    static Stream<Arguments> forgedHeaders() {
        String kid = CURRENT_KID;
        return Stream.of(
                header("alg none", "{\"alg\":\"none\",\"kid\":\"" + kid + "\"}"),
                header("alg RS256", "{\"alg\":\"RS256\",\"kid\":\"" + kid + "\"}"),
                header("alg ES256", "{\"alg\":\"ES256\",\"kid\":\"" + kid + "\"}"),
                header("alg eddsa in lower case", "{\"alg\":\"eddsa\",\"kid\":\"" + kid + "\"}"),
                header("alg EDDSA in upper case", "{\"alg\":\"EDDSA\",\"kid\":\"" + kid + "\"}"),
                header("alg Ed25519", "{\"alg\":\"Ed25519\",\"kid\":\"" + kid + "\"}"),
                header("alg absent", "{\"kid\":\"" + kid + "\"}"),
                header("alg null", "{\"alg\":null,\"kid\":\"" + kid + "\"}"),
                header("alg twice", "{\"alg\":\"EdDSA\",\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\"}"),
                header("kid absent", "{\"alg\":\"EdDSA\"}"),
                header("kid unknown", "{\"alg\":\"EdDSA\",\"kid\":\"no-existe\"}"),
                header("kid empty", "{\"alg\":\"EdDSA\",\"kid\":\"\"}"),
                header("kid a number", "{\"alg\":\"EdDSA\",\"kid\":7}"),
                header("kid null", "{\"alg\":\"EdDSA\",\"kid\":null}"),
                header("kid an array", "{\"alg\":\"EdDSA\",\"kid\":[\"" + kid + "\"]}"),
                header("kid twice", "{\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\",\"kid\":\"" + kid + "\"}"),
                header("kid in upper case", "{\"alg\":\"EdDSA\",\"kid\":\"" + kid.toUpperCase() + "\"}"),
                header("crit", "{\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\",\"crit\":[\"b64\"]}"),
                header("jku", "{\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\",\"jku\":\"https://x.test/k\"}"),
                header("jwk", "{\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\",\"jwk\":{\"kty\":\"OKP\"}}"),
                header("x5u", "{\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\",\"x5u\":\"https://x.test/c\"}"),
                header("typ", "{\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\",\"typ\":\"JWT\"}"),
                header("b64", "{\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\",\"b64\":false}"),
                header("members in reverse order", "{\"kid\":\"" + kid + "\",\"alg\":\"EdDSA\"}"),
                header("a space after the colon", "{\"alg\": \"EdDSA\",\"kid\":\"" + kid + "\"}"),
                header("a space after the comma", "{\"alg\":\"EdDSA\", \"kid\":\"" + kid + "\"}"),
                header("a leading space", " {\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\"}"),
                header("a trailing newline", "{\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\"}\n"),
                header("EdDSA with a unicode escape", "{\"alg\":\"Ed\\u0044SA\",\"kid\":\"" + kid + "\"}"),
                header("a JSON array", "[\"EdDSA\"]"),
                header("a JSON number", "7"),
                header("the empty object", "{}"),
                header("not JSON at all", "EdDSA " + kid));
    }

    private static Arguments header(String name, String headerJson) {
        return Arguments.of(name, token(headerJson, PAYLOAD, CURRENT.getPrivate()));
    }

    @ParameterizedTest(name = "[{index}] header: {0}")
    @MethodSource("forgedHeaders")
    void aForgedHeaderIsRejectedEvenWithAValidSignatureOfTheSystemKey(String name, String token) {
        assertRejected(token, TokenRejection.UNKNOWN_HEADER);
    }

    @Test
    void anHs256TokenSignedWithThePublicKeyBytesAsTheSecretIsRejected() throws Exception {
        String headerSegment = b64("{\"alg\":\"HS256\",\"kid\":\"" + CURRENT_KID + "\"}");
        String signingInput = headerSegment + "." + b64(PAYLOAD);
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(CURRENT.getPublic().getEncoded(), "HmacSHA256"));
        byte[] mac = hmac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));

        assertThat(mac).hasSize(32);
        assertRejected(signingInput + "." + b64(mac), TokenRejection.UNKNOWN_HEADER);
    }

    @Test
    void anAlgNoneTokenWithAnEmptySignatureIsMalformedAndWithAJunkSignatureIsAnUnknownHeader() {
        String headerSegment = b64("{\"alg\":\"none\",\"kid\":\"" + CURRENT_KID + "\"}");
        String unsigned = headerSegment + "." + b64(PAYLOAD) + ".";
        String junk = headerSegment + "." + b64(PAYLOAD) + "." + b64(new byte[64]);

        assertRejected(unsigned, TokenRejection.MALFORMED);
        assertRejected(junk, TokenRejection.UNKNOWN_HEADER);
    }

    @Test
    void theCompleteRfc8037A4TokenIsRejectedBecauseItsHeaderHasNoKid() {
        RfcVector vector = RfcVector.load();

        assertRejected(vector.compactToken(), TokenRejection.UNKNOWN_HEADER);
    }

    @Test
    void theCanonicalHeaderWithPaddingOrAnotherAlphabetIsAnUnknownHeader() {
        String payloadSegment = b64(PAYLOAD);

        assertRejected(tokenOverSegments(HEADER_SEGMENT + "=", payloadSegment, CURRENT.getPrivate()),
                TokenRejection.UNKNOWN_HEADER);
        assertRejected(tokenOverSegments(HEADER_SEGMENT + " ", payloadSegment, CURRENT.getPrivate()),
                TokenRejection.MALFORMED);
        assertRejected(tokenOverSegments(withNonCanonicalLastChar(HEADER_SEGMENT), payloadSegment,
                CURRENT.getPrivate()), TokenRejection.UNKNOWN_HEADER);
    }

    @Test
    void theHeaderOfAnotherKnownKidSelectsThatKidAndNotTheCurrentOne() {
        // The header names the previous key, but the signature is the current key's: the key is
        // chosen by the header and by nothing else, so the signature fails (I53).
        String token = issuedToken(PREVIOUS_KID, PAYLOAD, CURRENT.getPrivate());

        assertRejected(token, TokenRejection.BAD_SIGNATURE);
    }

    @Test
    void theFirstAndLastVisibleAsciiCharactersPassTheShapeStepAndTheNeighboursDoNot() {
        String payloadSegment = b64(PAYLOAD);
        String signatureSegment = b64(new byte[64]);
        String tail = "." + payloadSegment + "." + signatureSegment;

        // '!' (0x21) and '~' (0x7e) are visible, so step 1 lets them through and the unknown header
        // is what fails; a space (0x20) and DEL (0x7f) are not visible and fail step 1 itself.
        assertRejected("!" + tail, TokenRejection.UNKNOWN_HEADER);
        assertRejected("~" + tail, TokenRejection.UNKNOWN_HEADER);
        assertRejected(" " + tail, TokenRejection.MALFORMED);
        assertRejected((char) 0x7f + tail, TokenRejection.MALFORMED);
    }

    // ---------------------------------------------------------------------------------------------
    // Step 1 and step 3: shape, alphabet, canonical encoding and length (I55, I56)
    // ---------------------------------------------------------------------------------------------

    static Stream<Arguments> malformedTokens() {
        String good = issuedToken(CURRENT_KID, PAYLOAD, CURRENT.getPrivate());
        String[] segments = good.split("\\.");
        String payloadSegment = segments[1];
        String signatureSegment = segments[2];
        byte[] signature = signatureOf(good);
        return Stream.of(
                Arguments.of("the empty string", ""),
                Arguments.of("three dots only", ".."),
                Arguments.of("one segment", segments[0]),
                Arguments.of("two segments", segments[0] + "." + payloadSegment),
                Arguments.of("four segments", good + "." + signatureSegment),
                Arguments.of("an empty signature", segments[0] + "." + payloadSegment + "."),
                Arguments.of("an empty payload", segments[0] + ".." + signatureSegment),
                Arguments.of("an empty header", "." + payloadSegment + "." + signatureSegment),
                Arguments.of("a trailing dot", good + "."),
                Arguments.of("a leading dot", "." + good),
                Arguments.of("a space inside the payload",
                        tokenOverSegments(segments[0], payloadSegment.substring(0, 4) + " "
                                + payloadSegment.substring(4), CURRENT.getPrivate())),
                Arguments.of("a line feed inside the payload",
                        tokenOverSegments(segments[0], payloadSegment.substring(0, 4) + "\n"
                                + payloadSegment.substring(4), CURRENT.getPrivate())),
                Arguments.of("a space inside the signature", segments[0] + "." + payloadSegment + "."
                        + signatureSegment.substring(0, 10) + " " + signatureSegment.substring(10)),
                Arguments.of("a tab after the token", good + "\t"),
                Arguments.of("a NUL inside the signature", segments[0] + "." + payloadSegment + "."
                        + signatureSegment.substring(0, 10) + (char) 0 + signatureSegment.substring(10)),
                Arguments.of("a non-ASCII character in the payload",
                        tokenOverSegments(segments[0], payloadSegment + (char) 0xe9,
                                CURRENT.getPrivate())),
                Arguments.of("padding on the signature", good + "=="),
                Arguments.of("a signature of 63 bytes",
                        withSignature(good, Arrays.copyOf(signature, 63))),
                Arguments.of("a signature of 65 bytes",
                        withSignature(good, Arrays.copyOf(signature, 65))),
                Arguments.of("a signature of 32 bytes",
                        withSignature(good, Arrays.copyOf(signature, 32))),
                Arguments.of("a signature of 1 byte", withSignature(good, new byte[1])),
                Arguments.of("a signature that is not base64url in its length",
                        segments[0] + "." + payloadSegment + "." + signatureSegment.substring(0, 85)),
                Arguments.of("a signature with the standard alphabet",
                        standardAlphabetSignature()),
                Arguments.of("a signature with non-zero trailing bits",
                        withSignatureSegment(good, withNonCanonicalLastChar(signatureSegment))));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("malformedTokens")
    void aMalformedTokenIsRejectedAsMalformed(String name, String token) {
        assertRejected(token, TokenRejection.MALFORMED);
    }

    @Test
    void aNullTokenIsMalformedAndNeverAnException() {
        assertRejected(null, TokenRejection.MALFORMED);
    }

    @Test
    void aTokenOfExactly2048CharactersIsAcceptedAndOneOf2049IsMalformed() {
        // The header segment depends on the kid, so look for the kid whose length lets the payload
        // segment reach exactly 2048 and exactly 2049 characters in total.
        for (int kidLength = 1; kidLength <= 64; kidLength++) {
            String kid = "k".repeat(kidLength);
            KeyPair pair = newPair();
            CompactJws jws = new CompactJws(
                    SigningKeyRing.of(SigningKey.signing(kid, pair.getPublic(), pair.getPrivate())));
            int fixed = b64(canonicalHeaderJson(kid)).length() + 2 + 86;
            int payloadSegment = 2048 - fixed;
            if (payloadSegment % 4 == 1 || (payloadSegment + 1) % 4 == 1) {
                continue;
            }
            String atLimit = issuedToken(kid, payloadOfSegmentLength(payloadSegment), pair.getPrivate());
            String overLimit = issuedToken(kid, payloadOfSegmentLength(payloadSegment + 1),
                    pair.getPrivate());

            assertThat(atLimit).hasSize(2048);
            assertThat(overLimit).hasSize(2049);
            assertThat(jws.verify(atLimit).kid()).isEqualTo(kid);
            assertThatThrownBy(() -> jws.verify(overLimit)).isInstanceOfSatisfying(
                    TokenRejectedException.class,
                    e -> assertThat(e.rejection()).isEqualTo(TokenRejection.MALFORMED));
            return;
        }
        throw new AssertionError("no kid length reaches both boundary lengths");
    }

    // ---------------------------------------------------------------------------------------------
    // Step 4: the signature (I53, I54, I138)
    // ---------------------------------------------------------------------------------------------

    static Stream<Arguments> badSignatures() {
        String good = issuedToken(CURRENT_KID, PAYLOAD, CURRENT.getPrivate());
        byte[] signature = signatureOf(good);
        byte[] flipped = signature.clone();
        flipped[0] ^= 1;
        byte[] flippedLast = signature.clone();
        flippedLast[63] ^= (byte) 0x80;
        String[] segments = good.split("\\.");
        String payloadSegment = segments[1];
        // Same length, same alphabet, still canonical: only the signed content differs.
        String alteredPayload = (payloadSegment.charAt(5) == 'A' ? "B" : "A")
                + payloadSegment.substring(1);
        return Stream.of(
                Arguments.of("a key of another pair under the same kid",
                        issuedToken(CURRENT_KID, PAYLOAD, FOREIGN.getPrivate())),
                Arguments.of("a signature by the previous key under the current kid",
                        issuedToken(CURRENT_KID, PAYLOAD, PREVIOUS.getPrivate())),
                Arguments.of("the signature of another payload",
                        withSignature(good, signatureOf(issuedToken(CURRENT_KID, "{\"n\":2}",
                                CURRENT.getPrivate())))),
                Arguments.of("the first bit of the signature flipped", withSignature(good, flipped)),
                Arguments.of("the last bit of the signature flipped", withSignature(good, flippedLast)),
                Arguments.of("sixty-four zero bytes", withSignature(good, new byte[64])),
                Arguments.of("the malleable signature S plus L",
                        withSignature(good, malleableVersionOf(signature))),
                Arguments.of("the payload changed after signing",
                        segments[0] + "." + alteredPayload + "." + segments[2]),
                Arguments.of("the payload of another token",
                        segments[0] + "." + b64("{\"n\":2}") + "." + segments[2]));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("badSignatures")
    void aTokenWhoseSignatureDoesNotVerifyIsRejectedAsBadSignature(String name, String token) {
        assertRejected(token, TokenRejection.BAD_SIGNATURE);
    }

    @Test
    void theMalleableSignatureIsRejectedWithoutTheJdkExceptionEscaping() {
        String good = issuedToken(CURRENT_KID, PAYLOAD, CURRENT.getPrivate());
        String malleable = withSignature(good, malleableVersionOf(signatureOf(good)));

        // Probe S-2: the JDK throws SignatureException for S + L; the codec must end in a rejection.
        assertThatThrownBy(() -> JWS.verify(malleable))
                .isExactlyInstanceOf(TokenRejectedException.class)
                .hasMessage("BAD_SIGNATURE")
                .hasNoCause();
        assertThat(JWS.verify(good).kid()).isEqualTo(CURRENT_KID);
    }

    // ---------------------------------------------------------------------------------------------
    // Step 5: the payload, only after the signature (I55, I57)
    // ---------------------------------------------------------------------------------------------

    static Stream<Arguments> badPayloads() {
        return Stream.of(
                Arguments.of("a number", bytes("123")),
                Arguments.of("an array", bytes("[1,2]")),
                Arguments.of("a string", bytes("\"sub\"")),
                Arguments.of("the literal null", bytes("null")),
                Arguments.of("a boolean", bytes("true")),
                Arguments.of("a repeated claim name", bytes("{\"sub\":\"a\",\"sub\":\"b\"}")),
                Arguments.of("a repeated name in a nested object",
                        bytes("{\"a\":{\"x\":1,\"x\":2}}")),
                Arguments.of("two objects in a row", bytes("{\"a\":1}{\"b\":2}")),
                Arguments.of("an object followed by garbage", bytes("{\"a\":1} x")),
                Arguments.of("an unterminated object", bytes("{\"a\":1")),
                Arguments.of("not JSON", bytes("not json")),
                Arguments.of("bytes that are not UTF-8", new byte[] {'{', '"', 'a', '"', ':', '"',
                        (byte) 0xff, (byte) 0xfe, '"', '}'}));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @ParameterizedTest(name = "[{index}] payload: {0}")
    @MethodSource("badPayloads")
    void aValidlySignedPayloadThatIsNotAJsonObjectWithUniqueNamesIsMalformedClaims(String name,
            byte[] payload) {
        String token = tokenOverSegments(HEADER_SEGMENT, b64(payload), CURRENT.getPrivate());

        assertRejected(token, TokenRejection.MALFORMED_CLAIMS);
    }

    @Test
    void aPayloadSegmentWithPaddingIsRejectedEvenWhenTheSignatureCoversThePaddedText() {
        String segment = b64(payloadOfByteLengthModulo3(1));
        String padded = segment + "==";

        assertThat(JWS.verify(tokenOverSegments(HEADER_SEGMENT, segment, CURRENT.getPrivate())).kid())
                .isEqualTo(CURRENT_KID);
        assertRejected(tokenOverSegments(HEADER_SEGMENT, padded, CURRENT.getPrivate()),
                TokenRejection.MALFORMED_CLAIMS);
    }

    @Test
    void aPayloadSegmentWithTheStandardAlphabetIsRejectedEvenWhenTheSignatureCoversIt() {
        String urlSafe = payloadSegmentContaining('-');
        String standard = urlSafe.replace('-', '+');
        String slashed = payloadSegmentContaining('_').replace('_', '/');

        assertThat(JWS.verify(tokenOverSegments(HEADER_SEGMENT, urlSafe, CURRENT.getPrivate())).kid())
                .isEqualTo(CURRENT_KID);
        assertRejected(tokenOverSegments(HEADER_SEGMENT, standard, CURRENT.getPrivate()),
                TokenRejection.MALFORMED_CLAIMS);
        assertRejected(tokenOverSegments(HEADER_SEGMENT, slashed, CURRENT.getPrivate()),
                TokenRejection.MALFORMED_CLAIMS);
    }

    @Test
    void aPayloadSegmentWithNonZeroTrailingBitsIsRejectedEvenWhenTheSignatureCoversIt() {
        for (int modulo = 1; modulo <= 2; modulo++) {
            String canonical = b64(payloadOfByteLengthModulo3(modulo));

            assertThat(JWS.verify(tokenOverSegments(HEADER_SEGMENT, canonical, CURRENT.getPrivate()))
                    .kid()).isEqualTo(CURRENT_KID);
            assertRejected(tokenOverSegments(HEADER_SEGMENT, withNonCanonicalLastChar(canonical),
                    CURRENT.getPrivate()), TokenRejection.MALFORMED_CLAIMS);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The order of the steps, and what a rejection carries
    // ---------------------------------------------------------------------------------------------

    @Test
    void anUnknownHeaderIsDecidedBeforeTheSignatureSegmentIsLookedAt() {
        String token = b64("{\"alg\":\"none\",\"kid\":\"" + CURRENT_KID + "\"}") + "." + b64(PAYLOAD)
                + ".!!!";

        assertRejected(token, TokenRejection.UNKNOWN_HEADER);
    }

    @Test
    void aSignatureOfTheWrongLengthIsDecidedBeforeTheSignatureIsVerified() {
        String good = issuedToken(CURRENT_KID, PAYLOAD, CURRENT.getPrivate());
        String foreign = issuedToken(CURRENT_KID, PAYLOAD, FOREIGN.getPrivate());

        assertRejected(withSignature(foreign, Arrays.copyOf(signatureOf(foreign), 63)),
                TokenRejection.MALFORMED);
        assertRejected(withSignature(good, Arrays.copyOf(signatureOf(good), 65)),
                TokenRejection.MALFORMED);
    }

    @Test
    void thePayloadIsNeverParsedBeforeTheSignatureIsVerified() {
        // A payload that is not JSON, under a signature that is wrong: BAD_SIGNATURE, never
        // MALFORMED_CLAIMS, so an attacker without the key never reaches the JSON parser.
        String notJson = HEADER_SEGMENT + "." + b64("not json") + "."
                + b64(jdkSign(FOREIGN.getPrivate(), HEADER_SEGMENT + "." + b64("not json")));

        assertRejected(notJson, TokenRejection.BAD_SIGNATURE);
        assertRejected(tokenOverSegments(HEADER_SEGMENT, b64("not json"), CURRENT.getPrivate()),
                TokenRejection.MALFORMED_CLAIMS);
    }

    @Test
    void aRejectionNamesOnlyItsReasonAndCarriesNeitherTheTokenNorACause() {
        String secret = "SECRET-CLAIM-VALUE";
        String token = issuedToken(CURRENT_KID, "{\"s\":\"" + secret + "\"}", FOREIGN.getPrivate());

        assertThatThrownBy(() -> JWS.verify(token)).isInstanceOfSatisfying(TokenRejectedException.class,
                e -> {
                    assertThat(e.rejection()).isEqualTo(TokenRejection.BAD_SIGNATURE);
                    assertThat(e.getMessage()).isEqualTo("BAD_SIGNATURE");
                    assertThat(e.getCause()).isNull();
                    assertThat(e.getSuppressed()).isEmpty();
                    assertThat(e.toString()).doesNotContain(secret).doesNotContain(token);
                });
    }

    @Test
    void theSixRejectionReasonsAreTheOnesOfTheDesign() {
        assertThat(TokenRejection.values()).extracting(Enum::name).containsExactly("MALFORMED",
                "UNKNOWN_HEADER", "BAD_SIGNATURE", "MALFORMED_CLAIMS", "CLAIMS_INVALID", "EXPIRED");
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static void assertRejected(String token, TokenRejection expected) {
        assertThatThrownBy(() -> JWS.verify(token)).isExactlyInstanceOf(TokenRejectedException.class)
                .satisfies(e -> {
                    TokenRejectedException rejected = (TokenRejectedException) e;
                    assertThat(rejected.rejection()).isEqualTo(expected);
                    assertThat(rejected.getMessage()).isEqualTo(expected.name());
                    assertThat(rejected.getCause()).isNull();
                });
    }


    /** The last character replaced by its successor in the alphabet: same bytes, non-zero tail bits. */
    private static String withNonCanonicalLastChar(String segment) {
        int last = BASE64URL_ALPHABET.indexOf(segment.charAt(segment.length() - 1));
        assertThat(last % 4).as("the last character of a canonical segment").isZero();
        return segment.substring(0, segment.length() - 1) + BASE64URL_ALPHABET.charAt(last + 1);
    }

    private static String withSignatureSegment(String token, String signatureSegment) {
        return token.substring(0, token.lastIndexOf('.') + 1) + signatureSegment;
    }

    /** A token with a signature that contains '-' or '_' replaced by the standard-alphabet twin. */
    private static String standardAlphabetSignature() {
        for (int i = 0; i < 1000; i++) {
            String token = issuedToken(CURRENT_KID, "{\"i\":" + i + "}", CURRENT.getPrivate());
            String signature = token.substring(token.lastIndexOf('.') + 1);
            if (signature.indexOf('-') >= 0) {
                return withSignatureSegment(token, signature.replace('-', '+'));
            }
            if (signature.indexOf('_') >= 0) {
                return withSignatureSegment(token, signature.replace('_', '/'));
            }
        }
        throw new AssertionError("no signature with a URL-safe character in 1000 tries");
    }

    /** A JSON object whose byte length is congruent to the given value modulo 3. */
    private static String payloadOfByteLengthModulo3(int modulo) {
        String prefix = "{\"k\":\"";
        String suffix = "\"}";
        int fixed = prefix.length() + suffix.length();
        int filler = 0;
        while ((fixed + filler) % 3 != modulo) {
            filler++;
        }
        return prefix + "x".repeat(filler) + suffix;
    }

    /** A payload whose base64url segment contains the given URL-safe character. */
    private static String payloadSegmentContaining(char wanted) {
        for (int i = 0; i < 4096; i++) {
            String segment = b64("{\"p\":\"~~~???>>>" + i + "\"}");
            if (segment.indexOf(wanted) >= 0) {
                return segment;
            }
        }
        throw new AssertionError("no payload segment contains " + wanted);
    }

    /** A JSON object whose base64url segment has exactly the requested length. */
    private static String payloadOfSegmentLength(int segmentLength) {
        for (int length = 8; length < 4096; length++) {
            int segment = length % 3 == 0 ? length / 3 * 4 : length / 3 * 4 + length % 3 + 1;
            if (segment == segmentLength) {
                return "{\"p\":\"" + "x".repeat(length - 8) + "\"}";
            }
        }
        throw new AssertionError("unreachable segment length " + segmentLength);
    }
}
