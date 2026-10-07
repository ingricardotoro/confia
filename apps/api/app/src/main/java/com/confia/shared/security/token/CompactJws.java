package com.confia.shared.security.token;

import java.nio.charset.StandardCharsets;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The compact JWS of the administrative token, with a closed header (session-tokens-and-web-layer
 * design.md, decision 2): {@code B64U(header) "." B64U(payload) "." B64U(signature)}, where the
 * header is exactly {@code {"alg":"EdDSA","kid":"<kid>"}} and the signature is Ed25519 over the ASCII
 * of the first two segments.
 *
 * <p>The only issuer is this same process, so verification does not interpret a header: it compares
 * the received header segment, character by character, with the canonical segment the ring
 * precomputed for each kid. That one comparison closes every algorithm-confusion, {@code kid},
 * {@code crit}, {@code jku}, {@code jwk}, {@code typ}, spacing, ordering and duplicate-member trick
 * at once. {@link #verify} then decides, in this order and by the first failure: shape and length,
 * header, signature segment, signature, payload. Nothing of the payload is parsed before the
 * signature has verified, so a caller without a key never reaches the JSON parser.
 */
public final class CompactJws {

    /** The longest token accepted, in characters. */
    static final int MAX_TOKEN_LENGTH = 2048;

    private static final char SEGMENT_SEPARATOR = '.';
    private static final char FIRST_VISIBLE_ASCII = 0x21;
    private static final char LAST_VISIBLE_ASCII = 0x7e;

    /** Its own mapper, never Spring's: repeated member names and trailing content are refused. */
    private static final JsonMapper PAYLOAD_MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final SigningKeyRing ring;

    public CompactJws(SigningKeyRing ring) {
        this.ring = ring;
    }

    /** Signs the payload bytes, already serialized as a JSON object, with the current key. */
    public String sign(byte[] payload) {
        String signingInput = ring.currentHeaderSegment() + SEGMENT_SEPARATOR + Base64Url.encode(payload);
        byte[] signature = Ed25519Signatures.sign(ring.signingKey().privateKey(),
                signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + SEGMENT_SEPARATOR + Base64Url.encode(signature);
    }

    /**
     * Verifies a token and returns its kid and payload.
     *
     * @throws TokenRejectedException with the reason, for any token that is not accepted; nothing
     *     else escapes, whatever the input
     */
    public VerifiedJws verify(String token) {
        if (token == null || !hasThreeNonEmptyVisibleSegments(token)) {
            throw new TokenRejectedException(TokenRejection.MALFORMED);
        }
        int firstSeparator = token.indexOf(SEGMENT_SEPARATOR);
        int secondSeparator = token.indexOf(SEGMENT_SEPARATOR, firstSeparator + 1);
        SigningKey key = ring.keyForHeaderSegment(token.substring(0, firstSeparator))
                .orElseThrow(() -> new TokenRejectedException(TokenRejection.UNKNOWN_HEADER));
        byte[] signature = Base64Url.decode(token.substring(secondSeparator + 1))
                .filter(decoded -> decoded.length == Ed25519Signatures.SIGNATURE_LENGTH)
                .orElseThrow(() -> new TokenRejectedException(TokenRejection.MALFORMED));
        byte[] signingInput = token.substring(0, secondSeparator).getBytes(StandardCharsets.US_ASCII);
        if (!Ed25519Signatures.verify(key.publicKey(), signingInput, signature)) {
            throw new TokenRejectedException(TokenRejection.BAD_SIGNATURE);
        }
        byte[] payload = Base64Url.decode(token.substring(firstSeparator + 1, secondSeparator))
                .orElseThrow(() -> new TokenRejectedException(TokenRejection.MALFORMED_CLAIMS));
        return new VerifiedJws(key.kid(), parseObject(payload));
    }

    private static ObjectNode parseObject(byte[] payload) {
        JsonNode node;
        try {
            node = PAYLOAD_MAPPER.readTree(payload);
        } catch (JacksonException e) {
            throw new TokenRejectedException(TokenRejection.MALFORMED_CLAIMS);
        }
        if (node instanceof ObjectNode object) {
            return object;
        }
        throw new TokenRejectedException(TokenRejection.MALFORMED_CLAIMS);
    }

    /** Step 1: at most 2048 visible ASCII characters, exactly two dots, three non-empty segments. */
    private static boolean hasThreeNonEmptyVisibleSegments(String token) {
        if (token.length() > MAX_TOKEN_LENGTH) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < FIRST_VISIBLE_ASCII || c > LAST_VISIBLE_ASCII) {
                return false;
            }
        }
        int first = token.indexOf(SEGMENT_SEPARATOR);
        int second = token.indexOf(SEGMENT_SEPARATOR, first + 1);
        return first > 0 && second > first + 1 && second < token.length() - 1
                && token.indexOf(SEGMENT_SEPARATOR, second + 1) == -1;
    }
}
