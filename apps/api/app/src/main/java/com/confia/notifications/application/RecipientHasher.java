package com.confia.notifications.application;

import com.confia.notifications.domain.RecipientHash;
import com.confia.shared.security.Digests;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Computes the {@link RecipientHash} of an address: HMAC-SHA-256 of the normalized address with the
 * module's own key ({@code NOTIFICATIONS_RECIPIENT_HASH_KEY}, at least {@value
 * Digests#MIN_HMAC_KEY_BYTES} bytes), through {@link Digests} (design.md decision 8).
 *
 * <p>The key is the module's alone, never shared with another purpose: the same address hashes to
 * unrelated values under two keys, and without the key the hash cannot be derived from a list of
 * addresses. Rotating it breaks the correlation of historical rows; the runbook documents that. The
 * hasher keeps its own copy of the key and its textual form is redacted.
 */
public final class RecipientHasher {

    private final byte[] key;

    /**
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IllegalArgumentException if {@code key} is shorter than {@value
     *     Digests#MIN_HMAC_KEY_BYTES} bytes; the message states the rule and never the key
     */
    public RecipientHasher(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length < Digests.MIN_HMAC_KEY_BYTES) {
            throw new IllegalArgumentException("the recipient hash key must have at least "
                    + Digests.MIN_HMAC_KEY_BYTES + " bytes");
        }
        this.key = key.clone();
    }

    public RecipientHash hash(String address) {
        byte[] message = RecipientHash.normalize(address).getBytes(StandardCharsets.UTF_8);
        return new RecipientHash(HexFormat.of().formatHex(Digests.hmacSha256(key, message)));
    }

    @Override
    public String toString() {
        return "RecipientHasher[redacted]";
    }
}
