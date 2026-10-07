package com.confia.shared.security.token;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
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

    /** The deepest nesting a payload may have; a token cannot hold much more, and none needs any. */
    static final int MAX_NESTING_DEPTH = 500;

    private static final char BYTE_ORDER_MARK = '﻿';

    /**
     * Its own mapper, never Spring's: repeated member names and trailing content are refused, and
     * the nesting limit is stated here instead of being whatever the library defaults to.
     */
    private static final JsonMapper PAYLOAD_MAPPER = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(
                    StreamReadConstraints.builder().maxNestingDepth(MAX_NESTING_DEPTH).build())
            .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final SigningKeyRing ring;

    public CompactJws(SigningKeyRing ring) {
        this.ring = ring;
    }

    /**
     * Signs the payload bytes with the current key.
     *
     * @param payload a JSON object in strict UTF-8 with unique member names
     * @throws IllegalArgumentException when the payload is not such an object, or when the token
     *     would be longer than {@value #MAX_TOKEN_LENGTH} characters, which {@link #verify} would
     *     refuse; the message never repeats any of the payload
     */
    public String sign(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (readObject(payload).isEmpty()) {
            throw new IllegalArgumentException("the payload is not a JSON object with unique names");
        }
        String signingInput = ring.currentHeaderSegment() + SEGMENT_SEPARATOR + Base64Url.encode(payload);
        byte[] signature = Ed25519Signatures.sign(ring.signingKey().privateKey(),
                signingInput.getBytes(StandardCharsets.US_ASCII));
        String token = signingInput + SEGMENT_SEPARATOR + Base64Url.encode(signature);
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    "the token would be longer than " + MAX_TOKEN_LENGTH + " characters");
        }
        return token;
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
        ObjectNode claims = readObject(payload)
                .orElseThrow(() -> new TokenRejectedException(TokenRejection.MALFORMED_CLAIMS));
        return new VerifiedJws(key.kid(), claims);
    }

    /**
     * The payload as a JSON object, or empty when it is not one. The bytes are decoded as strict
     * UTF-8 first, so that the parser never detects an encoding by itself (UTF-16, UTF-32, a byte
     * order mark) and an ill-formed sequence is refused instead of replaced (follow-up S4).
     */
    private static Optional<ObjectNode> readObject(byte[] payload) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = PAYLOAD_MAPPER.readTree(text);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        return node instanceof ObjectNode object ? Optional.of(object) : Optional.empty();
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
