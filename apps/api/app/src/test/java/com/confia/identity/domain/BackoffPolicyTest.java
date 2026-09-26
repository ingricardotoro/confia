package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * {@link BackoffPolicy}, the exponential-backoff rule itself (design.md §6.3; specs/identity/spec.md,
 * requirement "Retardo por intentos fallidos con retroceso exponencial", the four scenarios that do
 * not need a real repository or a real account: the fifth ("cuenta inexistente") and sixth
 * ("contraseña correcta durante el retroceso") scenarios repeat this exact rule over an adapter and
 * a use case that do not exist until PR C3b — this class does not special-case account existence at
 * all, which is exactly why the same four scenarios below already cover it in substance).
 *
 * <p>No {@link java.time.Clock} anywhere in this class or its test: every {@code Instant} the rule
 * needs travels in as a plain argument (design.md decision 5, "el reloj inyectado y la regla en
 * {@code identity.domain}"), which is what lets the 30-minute expiry scenario be proven with two
 * fixed instants instead of an actual wait.
 */
class BackoffPolicyTest {

    private static final int TRIES = 200;

    private final BackoffPolicy policy = new BackoffPolicy();

    @Test
    void delaysFromTheThirdConsecutiveFailureWithOneSecond() {
        Instant first = Instant.parse("2026-03-10T08:00:00Z");
        Instant second = Instant.parse("2026-03-10T08:00:20Z");
        Instant third = Instant.parse("2026-03-10T08:00:40Z");

        BackoffState afterFirst = applyFailure(BackoffState.initial(first), first);
        BackoffState afterSecond = applyFailure(afterFirst, second);
        int thirdOrdinal = policy.attemptOrdinal(afterSecond, third);
        Duration thirdDelay = policy.delayFor(thirdOrdinal);

        assertThat(thirdOrdinal).isEqualTo(3);
        assertThat(thirdDelay).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void theExponentialProgressionIsCappedAt900Seconds() {
        Instant lastOfTwelve = Instant.parse("2026-03-10T08:00:00Z");
        BackoffState priorToThirteenth = new BackoffState(12, lastOfTwelve);
        Instant thirteenthAttempt = Instant.parse("2026-03-10T08:15:00Z");

        int ordinal = policy.attemptOrdinal(priorToThirteenth, thirteenthAttempt);
        Duration delay = policy.delayFor(ordinal);

        assertThat(ordinal).isEqualTo(13);
        assertThat(delay).isEqualTo(BackoffPolicy.CAP);
        assertThat(delay).isEqualTo(Duration.ofSeconds(900));
    }

    @Test
    void theCounterExpiresAfter30MinutesWithoutAnyAttempt() {
        Instant thirdFailureAt = Instant.parse("2026-03-10T09:00:00Z");
        BackoffState priorToNewCycle = new BackoffState(3, thirdFailureAt);
        Instant newAttempt = thirdFailureAt.plus(Duration.ofMinutes(31));

        int ordinal = policy.attemptOrdinal(priorToNewCycle, newAttempt);
        Duration delay = policy.delayFor(ordinal);

        assertThat(ordinal).isEqualTo(1);
        assertThat(delay).isEqualTo(Duration.ZERO);
    }

    @Test
    void aSuccessfulLoginClearsTheAccountsCounter() {
        Instant twoFailuresAt = Instant.parse("2026-03-10T10:00:00Z");
        BackoffState priorToSuccess = new BackoffState(2, twoFailuresAt);
        Instant successAt = Instant.parse("2026-03-10T10:05:00Z");

        BackoffState afterSuccess = policy.afterSuccess(successAt);

        assertThat(afterSuccess.consecutiveFailures()).isZero();
        assertThat(afterSuccess.lastAttemptAt()).isEqualTo(successAt);

        Instant nextFailureAt = Instant.parse("2026-03-10T10:06:00Z");
        int nextOrdinal = policy.attemptOrdinal(afterSuccess, nextFailureAt);
        Duration nextDelay = policy.delayFor(nextOrdinal);

        assertThat(nextOrdinal).isEqualTo(1);
        assertThat(nextDelay).isEqualTo(Duration.ZERO);
    }

    @Test
    void aCorrectPasswordDuringBackoffStillPaysTheDelayThenSucceeds() {
        Instant thirdFailureAt = Instant.parse("2026-03-10T12:00:00Z");
        BackoffState priorToFourth = new BackoffState(3, thirdFailureAt);
        Instant fourthAttemptAt = Instant.parse("2026-03-10T12:00:10Z");

        int ordinal = policy.attemptOrdinal(priorToFourth, fourthAttemptAt);
        Duration delay = policy.delayFor(ordinal);

        assertThat(ordinal).isEqualTo(4);
        assertThat(delay).isEqualTo(Duration.ofSeconds(2));

        BackoffState afterThisSuccess = policy.afterSuccess(fourthAttemptAt);
        assertThat(afterThisSuccess.consecutiveFailures()).isZero();
    }

    @Property(tries = TRIES)
    void delayIsNeverNegativeAndNeverExceedsTheCap(@ForAll @IntRange(min = 1, max = 10_000) int attemptOrdinal) {
        Duration delay = policy.delayFor(attemptOrdinal);

        assertThat(delay).isGreaterThanOrEqualTo(Duration.ZERO);
        assertThat(delay).isLessThanOrEqualTo(BackoffPolicy.CAP);
    }

    @Property(tries = TRIES)
    void delayIsZeroBelowTheFirstDelayedAttempt(
            @ForAll @IntRange(min = 1, max = BackoffPolicy.FIRST_DELAYED_ATTEMPT - 1) int attemptOrdinal) {
        assertThat(policy.delayFor(attemptOrdinal)).isEqualTo(Duration.ZERO);
    }

    @Property(tries = TRIES)
    void delayIsMonotonicNonDecreasingInTheAttemptOrdinal(
            @ForAll("ascendingOrdinalPairs") int[] pair) {
        Duration earlier = policy.delayFor(pair[0]);
        Duration later = policy.delayFor(pair[1]);

        assertThat(later).isGreaterThanOrEqualTo(earlier);
    }

    @Provide
    Arbitrary<int[]> ascendingOrdinalPairs() {
        return Arbitraries.integers().between(1, 1000)
                .tuple2()
                .map(pair -> new int[] {Math.min(pair.get1(), pair.get2()), Math.max(pair.get1(), pair.get2())});
    }

    private BackoffState applyFailure(BackoffState prior, Instant now) {
        int ordinal = policy.attemptOrdinal(prior, now);
        return policy.afterFailure(ordinal, now);
    }
}
