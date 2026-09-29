package com.confia.identity.domain;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The pure RFC 6238 (TOTP) algorithm, over its HOTP (RFC 4226) core: no clock of its own, no I/O,
 * no repository (column-encryption-and-mfa-totp design.md, decision 7). Parameters fixed by
 * docs/03-seguridad.md §4.3: HMAC-SHA-1, 6 digits, 30-second period.
 *
 * <p>Verified against the six RFC 6238 appendix B vectors {@code exploration.md}'s appendix
 * confirmed against {@code rfc-editor.org} ({@link TotpAlgorithmTest}), including the case whose
 * six-digit value begins with leading zeros.
 */
public final class TotpAlgorithm {

    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final int CODE_DIGITS = 6;
    private static final int CODE_MODULUS = 1_000_000;

    private TotpAlgorithm() {
    }

    /** {@code floor(instant.epochSecond / period.seconds)} — RFC 6238's own counter derivation. */
    public static long counterFor(Instant instant, Duration period) {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(period, "period");
        return Math.floorDiv(instant.getEpochSecond(), period.toSeconds());
    }

    /**
     * Generates the six-digit TOTP code for {@code counter} under {@code secret}, following RFC
     * 4226's dynamic truncation over an HMAC-SHA-1 digest.
     *
     * <p><b>Never returns an {@code int}, never an unpadded string</b> (exploration.md, appendix,
     * "el detalle que hay que cuidar"): the RFC 6238 vector for epoch second 1234567890 is
     * {@code "005924"}, with two leading zeros — a caller that compared the numeric value 5924
     * would pass this exact case while failing against any real authenticator app. {@link
     * String#format(Locale, String, Object...)} with {@code "%06d"} is what guarantees the
     * zero-padding survives.
     */
    public static TotpCode generate(byte[] secret, long counter) {
        Objects.requireNonNull(secret, "secret");
        byte[] hmac = hmacSha1(secret, counterBytes(counter));
        int offset = hmac[hmac.length - 1] & 0x0F;
        int binary = ((hmac[offset] & 0x7F) << 24)
                | ((hmac[offset + 1] & 0xFF) << 16)
                | ((hmac[offset + 2] & 0xFF) << 8)
                | (hmac[offset + 3] & 0xFF);
        int truncated = binary % CODE_MODULUS;
        return new TotpCode(String.format(Locale.ROOT, "%0" + CODE_DIGITS + "d", truncated));
    }

    private static byte[] counterBytes(long counter) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (counter & 0xFF);
            counter >>>= 8;
        }
        return bytes;
    }

    private static byte[] hmacSha1(byte[] secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(HMAC_ALGORITHM + " must always be available on the JVM", e);
        }
    }
}
