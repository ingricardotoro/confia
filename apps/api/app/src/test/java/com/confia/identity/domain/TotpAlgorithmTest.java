package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * {@link TotpAlgorithm} against the six RFC 6238 test vectors {@code exploration.md}'s appendix
 * already verified against {@code rfc-editor.org} (column-encryption-and-mfa-totp design.md,
 * decision 7). The shared secret is the RFC's own ASCII string {@code
 * "12345678901234567890"} — a published test vector, not a real secret (CLAUDE.md regla 13).
 *
 * <p>Every six-digit value asserted below is <b>derived</b> from the RFC's own eight-digit value
 * by truncation (the appendix's identity: {@code (Snum mod 10^8) mod 10^6 = Snum mod 10^6}), never
 * transcribed directly from the RFC — each assertion cites the eight-digit RFC value in a comment
 * so the derivation is visible, not just claimed.
 */
class TotpAlgorithmTest {

    /** RFC 6238, appendix B: the shared secret for every SHA-1 vector, ASCII-encoded. */
    private static final byte[] SHARED_SECRET =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    private static final Duration PERIOD = Duration.ofSeconds(30);

    @Test
    void time59YieldsTheDerivedSixDigitCode() {
        // RFC 6238 appendix B, eight digits: "94287082".
        assertThat(generate(59)).isEqualTo("287082");
    }

    @Test
    void time1111111109YieldsTheDerivedSixDigitCode() {
        // RFC 6238 appendix B, eight digits: "07081804".
        assertThat(generate(1111111109L)).isEqualTo("081804");
    }

    @Test
    void time1111111111YieldsTheDerivedSixDigitCode() {
        // RFC 6238 appendix B, eight digits: "14050471".
        assertThat(generate(1111111111L)).isEqualTo("050471");
    }

    /**
     * The case exploration.md's appendix warns about explicitly: the six-digit value begins with
     * two zeros. Asserting with {@code isEqualTo("005924")} — a {@link String}, never a numeric
     * comparison — is what demonstrates the warning ("un entero... pasaría comparando 5924")
     * instead of merely citing it: an implementation that returned the {@code int} 5924, or a
     * string without the leading zeros, would fail this exact assertion while it would pass a
     * numeric {@code isEqualTo(5924)}.
     */
    @Test
    void time1234567890YieldsTheSixDigitCodeWithLeadingZeros() {
        // RFC 6238 appendix B, eight digits: "89005924".
        String code = generate(1234567890L);

        assertThat(code).isEqualTo("005924");
        assertThat(code).hasSize(6);
    }

    @Test
    void time2000000000YieldsTheDerivedSixDigitCode() {
        // RFC 6238 appendix B, eight digits: "69279037".
        assertThat(generate(2_000_000_000L)).isEqualTo("279037");
    }

    @Test
    void time20000000000YieldsTheDerivedSixDigitCode() {
        // RFC 6238 appendix B, eight digits: "65353130".
        assertThat(generate(20_000_000_000L)).isEqualTo("353130");
    }

    @Test
    void counterForFloorDividesTheEpochSecondByThePeriod() {
        long counter = TotpAlgorithm.counterFor(Instant.ofEpochSecond(59), PERIOD);

        assertThat(counter).isEqualTo(1L);
    }

    private static String generate(long epochSecond) {
        long counter = TotpAlgorithm.counterFor(Instant.ofEpochSecond(epochSecond), PERIOD);
        return TotpAlgorithm.generate(SHARED_SECRET, counter).value();
    }
}
