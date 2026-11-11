package com.confia.shared.crypto;

import com.confia.shared.security.Digests;
import java.util.Arrays;

/**
 * HMAC-SHA-256 as RFC 2104 defines it, built over {@link Digests#sha256}: the confinement rule of
 * hashing and signing utilities (SigningAndHashingConfinementTest) keeps {@code javax.crypto.Mac} inside
 * {@code identity} and {@code shared.security}, and {@code Digests} is the one door the rest of the code
 * is given. The tests check this class against the vectors of RFC 4231 and against {@code Mac}.
 */
final class HmacSha256 {

    private static final int BLOCK_BYTES = 64;
    private static final byte INNER_PAD = 0x36;
    private static final byte OUTER_PAD = 0x5c;

    private HmacSha256() {
    }

    static byte[] mac(byte[] key, byte[] message) {
        byte[] block = new byte[BLOCK_BYTES];
        byte[] effectiveKey = key.length > BLOCK_BYTES ? Digests.sha256(key) : key;
        System.arraycopy(effectiveKey, 0, block, 0, effectiveKey.length);

        byte[] inner = new byte[BLOCK_BYTES + message.length];
        byte[] outer = new byte[BLOCK_BYTES + Digests.sha256(new byte[0]).length];
        for (int i = 0; i < BLOCK_BYTES; i++) {
            inner[i] = (byte) (block[i] ^ INNER_PAD);
            outer[i] = (byte) (block[i] ^ OUTER_PAD);
        }
        System.arraycopy(message, 0, inner, BLOCK_BYTES, message.length);
        byte[] innerHash = Digests.sha256(inner);
        System.arraycopy(innerHash, 0, outer, BLOCK_BYTES, innerHash.length);
        byte[] result = Digests.sha256(outer);
        Arrays.fill(block, (byte) 0);
        Arrays.fill(inner, (byte) 0);
        Arrays.fill(outer, (byte) 0);
        return result;
    }
}
