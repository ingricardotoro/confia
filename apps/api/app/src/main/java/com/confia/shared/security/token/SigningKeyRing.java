package com.confia.shared.security.token;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The keys a process signs and verifies with: one {@code current} key that signs and verifies and,
 * during a rotation, one {@code previous} key that only verifies (session-tokens-and-web-layer
 * design.md, decision 3). For every kid it holds the canonical header segment, computed once, which
 * is what the codec compares a received header with (decision 2, step 2).
 *
 * <p>This is the in-memory shape the codec needs. The loader that builds a ring from the process
 * environment, with its startup checks, is added by the next task without changing this signature.
 */
public final class SigningKeyRing {

    private final SigningKey current;
    private final String currentHeaderSegment;
    private final Map<String, SigningKey> keysByHeaderSegment;

    private SigningKeyRing(SigningKey current, SigningKey previous) {
        if (!current.hasPrivateKey()) {
            throw new IllegalArgumentException("the current key must hold a private key");
        }
        this.current = current;
        this.currentHeaderSegment = headerSegmentOf(current.kid());
        Map<String, SigningKey> keys = new HashMap<>();
        keys.put(currentHeaderSegment, current);
        if (previous != null) {
            if (previous.hasPrivateKey()) {
                throw new IllegalArgumentException("the previous key must never hold a private key");
            }
            if (previous.kid().equals(current.kid())) {
                throw new IllegalArgumentException("the current and previous kid must differ");
            }
            keys.put(headerSegmentOf(previous.kid()), previous);
        }
        this.keysByHeaderSegment = Map.copyOf(keys);
    }

    public static SigningKeyRing of(SigningKey current) {
        return new SigningKeyRing(current, null);
    }

    public static SigningKeyRing of(SigningKey current, SigningKey previous) {
        return new SigningKeyRing(current, previous);
    }

    /** The key that signs. */
    public SigningKey signingKey() {
        return current;
    }

    /** The canonical header segment of the signing key, the one every issued token carries. */
    public String currentHeaderSegment() {
        return currentHeaderSegment;
    }

    /**
     * The key whose canonical header segment is exactly the given text, or empty. The match is a
     * plain equality on the whole segment: nothing of a header supplied by a client is decoded.
     */
    public Optional<SigningKey> keyForHeaderSegment(String headerSegment) {
        return Optional.ofNullable(keysByHeaderSegment.get(headerSegment));
    }

    private static String headerSegmentOf(String kid) {
        String header = "{\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\"}";
        return Base64Url.encode(header.getBytes(StandardCharsets.US_ASCII));
    }
}
