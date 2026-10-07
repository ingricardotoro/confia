package com.confia.shared.security.token;

import java.util.Base64;
import java.util.Optional;

/**
 * Base64url without padding, strict and canonical (session-tokens-and-web-layer design.md, decision
 * 2). Encoding is the JDK's. Decoding accepts a text only when encoding what it decodes to gives that
 * very text back, so the same bytes can never have two texts: padding, the standard alphabet,
 * whitespace and the non-zero trailing bits that the JDK decoder accepts in silence are all refused.
 */
public final class Base64Url {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private Base64Url() {
    }

    public static String encode(byte[] bytes) {
        return ENCODER.encodeToString(bytes);
    }

    /** The decoded bytes, or empty when the text is not the canonical encoding of any byte array. */
    public static Optional<byte[]> decode(String text) {
        byte[] bytes;
        try {
            bytes = DECODER.decode(text);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return ENCODER.encodeToString(bytes).equals(text) ? Optional.of(bytes) : Optional.empty();
    }
}
