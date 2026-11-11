package com.confia.identity.web;

import com.confia.identity.domain.TotpAlgorithm;
import java.time.Duration;
import java.time.Instant;

/**
 * Computes the one-time code an authenticator application would show for a secret the enrollment
 * endpoint answered, with the production algorithm and the production period, so a test can complete a
 * real second factor without a double (rbac-permission-matrix-and-audit-integration design.md D19). The
 * process under test reads the real clock, so the code is computed from the real clock as well.
 */
final class TotpCodes {

    private static final Duration PERIOD = Duration.ofSeconds(30);
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpCodes() {
    }

    /** The code of {@code base32Secret} for the period that holds the present instant. */
    static String currentCode(String base32Secret) {
        long counter = TotpAlgorithm.counterFor(Instant.now(), PERIOD);
        return TotpAlgorithm.generate(decodeBase32(base32Secret), counter).value();
    }

    private static byte[] decodeBase32(String text) {
        byte[] out = new byte[text.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char c : text.toCharArray()) {
            buffer = (buffer << 5) | BASE32_ALPHABET.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return out;
    }
}
