package com.confia.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The verification rule over an already-decrypted TOTP secret: tolerance window of ±1 period and
 * counter-based anti-repetition (column-encryption-and-mfa-totp design.md, decision 7;
 * docs/03-seguridad.md §4.3). No repository, no clock of its own, no I/O — {@code now} always
 * travels in as a plain argument, the same reasoning {@link BackoffPolicy} already applies to
 * itself.
 */
public final class TotpVerificationPolicy {

    private static final int TOLERANCE_WINDOW = 1;

    /**
     * Tries, in order, the counters {@code [expected-1, expected, expected+1]} against {@code
     * secret} and returns the first candidate that (a) matches the code {@code presented} and (b)
     * is strictly greater than {@code lastAcceptedCounter}. {@link Optional#empty()} when no
     * candidate satisfies both — including a code that is mathematically valid for a counter that
     * was already accepted (specs/identity/spec.md, "Un código ya aceptado no puede reutilizarse,
     * aunque siga siendo matemáticamente válido").
     *
     * <p><b>Compares with {@link MessageDigest#isEqual}, never {@code String.equals} or {@code
     * ==}</b>: comparing two six-character strings with ordinary equality leaks, through timing,
     * how many of the presented digits matched before the first mismatch — the same side-channel
     * {@code BouncyCastleArgon2PasswordHasher.matches(...)} already closes for password hashes.
     */
    public Optional<Long> matchingCounter(byte[] secret, long lastAcceptedCounter, Instant now,
            Duration period, TotpCode presented) {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(presented, "presented");

        long expected = TotpAlgorithm.counterFor(now, period);
        byte[] presentedBytes = presented.value().getBytes(StandardCharsets.UTF_8);
        for (long candidate = expected - TOLERANCE_WINDOW;
                candidate <= expected + TOLERANCE_WINDOW; candidate++) {
            if (candidate <= lastAcceptedCounter) {
                continue;
            }
            TotpCode generated = TotpAlgorithm.generate(secret, candidate);
            if (MessageDigest.isEqual(generated.value().getBytes(StandardCharsets.UTF_8),
                    presentedBytes)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
