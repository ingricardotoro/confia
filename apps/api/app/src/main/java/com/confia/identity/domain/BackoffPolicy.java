package com.confia.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The exponential-backoff rule itself, and nothing else: no repository, no clock, no I/O
 * (design.md §6.3; specs/identity/spec.md, requirement "Retardo por intentos fallidos con
 * retroceso exponencial"). Every {@link Instant} this class needs travels in as a plain argument —
 * it never reads its own clock — which is exactly what {@code design.md} decision 5 requires so the
 * 30-minute expiry can be proven with two fixed instants instead of an actual wait.
 *
 * <p>This rule does not know, and must never be made to know, whether the identifier it is applied
 * to corresponds to an existing account: {@link #attemptOrdinal(BackoffState, Instant)} and
 * {@link #delayFor(int)} are identifier-agnostic by construction, which is the structural half of
 * the uniformity {@code docs/03-seguridad.md} §4.6 asks for (design.md, decision 3, "el código del
 * retroceso es literalmente el mismo").
 *
 * <p>The delay demora la respuesta, NUNCA la deniega: this class has no method that rejects
 * anything. A caller that reaches {@link #delayFor(int)} with the correct password still
 * authenticates, after paying the delay (specs/identity/spec.md, "Una contraseña correcta durante
 * el retroceso tiene éxito tras el retardo").
 */
public final class BackoffPolicy {

    /** The attempt ordinal, within its cycle, from which a delay first applies (docs/03 §4.4). */
    public static final int FIRST_DELAYED_ATTEMPT = 3;

    /** The maximum delay this policy ever returns, regardless of how many failures accumulate. */
    public static final Duration CAP = Duration.ofSeconds(900);

    /**
     * How long a cycle survives without any attempt before the next attempt starts a fresh one
     * (specs/identity/spec.md, "El contador expira a los 30 minutos sin intentos").
     */
    public static final Duration COUNTER_WINDOW = Duration.ofMinutes(30);

    /**
     * The ordinal of the attempt in progress within its still-live cycle — {@code
     * prior.consecutiveFailures() + 1} when the cycle is still within {@link #COUNTER_WINDOW} of
     * its last attempt, or {@code 1} when the cycle has expired and this attempt starts a new one.
     * This ordinal does not depend on how the attempt in progress will resolve (design.md, decision
     * 10, "El ordinal no depende del desenlace"): the same ordinal, and therefore the same delay,
     * applies whether the credential eventually offered turns out correct or not.
     */
    public int attemptOrdinal(BackoffState prior, Instant now) {
        Objects.requireNonNull(prior, "prior");
        Objects.requireNonNull(now, "now");
        Duration sinceLastAttempt = Duration.between(prior.lastAttemptAt(), now);
        if (sinceLastAttempt.compareTo(COUNTER_WINDOW) > 0) {
            return 1;
        }
        return prior.consecutiveFailures() + 1;
    }

    /**
     * {@code 0} for any ordinal below {@link #FIRST_DELAYED_ATTEMPT}; otherwise {@code
     * 2^(attemptOrdinal - FIRST_DELAYED_ATTEMPT)} seconds, capped at {@link #CAP}. The exponent is
     * bounded to avoid an overflowing shift for a pathologically large ordinal: any exponent of 10
     * or more already yields 1024 seconds, past the 900-second cap, so nothing above that bound
     * ever needs to be computed exactly.
     */
    public Duration delayFor(int attemptOrdinal) {
        if (attemptOrdinal < 1) {
            throw new IllegalArgumentException(
                    "attemptOrdinal must be at least 1, was " + attemptOrdinal);
        }
        if (attemptOrdinal < FIRST_DELAYED_ATTEMPT) {
            return Duration.ZERO;
        }
        int exponent = attemptOrdinal - FIRST_DELAYED_ATTEMPT;
        if (exponent >= 10) {
            return CAP;
        }
        Duration uncapped = Duration.ofSeconds(1L << exponent);
        return uncapped.compareTo(CAP) > 0 ? CAP : uncapped;
    }

    /** The state to persist after a failed attempt at ordinal {@code attemptOrdinal}. */
    public BackoffState afterFailure(int attemptOrdinal, Instant now) {
        Objects.requireNonNull(now, "now");
        return new BackoffState(attemptOrdinal, now);
    }

    /**
     * The state to persist after a successful login: the counter clears
     * (specs/identity/spec.md, "Un inicio de sesión exitoso limpia el contador de la cuenta"), even
     * when the credential that succeeded arrived mid-backoff.
     */
    public BackoffState afterSuccess(Instant now) {
        Objects.requireNonNull(now, "now");
        return new BackoffState(0, now);
    }
}
