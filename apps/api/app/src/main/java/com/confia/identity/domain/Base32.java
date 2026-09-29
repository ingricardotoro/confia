package com.confia.identity.domain;

/**
 * Base32 as RFC 4648 section 6 defines it: the alphabet {@code A-Z2-7}, five bytes encoded into
 * eight characters, and {@code =} padding for a trailing partial group.
 *
 * <p>Hand-rolled rather than pulled in as a dependency, the same call the change already made for
 * the TOTP algorithm itself: the whole encoder is one loop over a bit accumulator, and RFC 4648
 * section 10 publishes the seven vectors that pin it down, which {@code Base32Test} transcribes.
 *
 * <p>Package-private on purpose. Its only caller is {@link PlainTotpSecret#base32()}, the one place
 * this change renders a TOTP secret for a human to transcribe. A public encoder in the domain
 * package would invite callers to render other secrets, and this codec carries no redaction of its
 * own — whatever it is handed comes back legible by construction.
 */
final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int BITS_PER_CHARACTER = 5;
    private static final int BITS_PER_BYTE = 8;
    /** Eight characters per five input bytes, so a full group is 40 bits. */
    private static final int CHARACTERS_PER_GROUP = 8;

    private Base32() {
    }

    static String encode(byte[] value) {
        StringBuilder encoded = new StringBuilder();
        int accumulator = 0;
        int pendingBits = 0;
        for (byte b : value) {
            accumulator = (accumulator << BITS_PER_BYTE) | (b & 0xFF);
            pendingBits += BITS_PER_BYTE;
            while (pendingBits >= BITS_PER_CHARACTER) {
                pendingBits -= BITS_PER_CHARACTER;
                encoded.append(ALPHABET.charAt((accumulator >>> pendingBits) & 0x1F));
            }
        }
        if (pendingBits > 0) {
            // The leftover bits are the high bits of the last character: shifting them up rather
            // than down is what makes "f" encode to MY====== and not to AY======.
            encoded.append(ALPHABET.charAt(
                    (accumulator << (BITS_PER_CHARACTER - pendingBits)) & 0x1F));
        }
        while (encoded.length() % CHARACTERS_PER_GROUP != 0) {
            encoded.append('=');
        }
        return encoded.toString();
    }
}
