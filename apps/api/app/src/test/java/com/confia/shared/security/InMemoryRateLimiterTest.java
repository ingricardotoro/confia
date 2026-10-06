package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.RateLimitDecision.Admitted;
import com.confia.shared.security.RateLimitDecision.Limited;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The scenarios of {@link InMemoryRateLimiter} that do not need a full table: the request layer, the
 * failure layer, the policy and the declared limitation of an in-memory limiter (the bounded table
 * has its own class, task 3.1b) (web-edge-foundations design.md,
 * decisions 15 and 16; specs/web-edge, the requirements from "El puerto RateLimiter cuenta
 * peticiones y fallos como dimensiones separadas" to "La limitación del limitador en memoria está
 * declarada por escrito"). Time is a {@link MutableClock}: nothing here sleeps.
 */
class InMemoryRateLimiterTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration SECOND = Duration.ofSeconds(1);
    private static final Duration NANO = Duration.ofNanos(1);

    /** The defaults of the administrative login policy: layer 1 and layer 2 of docs/03. */
    private static final RateLimitPolicy ADMIN_LOGIN =
            new RateLimitPolicy(10, MINUTE, 10, TEN_MINUTES, MINUTE, HOUR, 1_000);

    private static final ClientAddress CLIENT = ip("203.0.113.9");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    private static ClientAddress ip(String literal) {
        return ClientAddress.parseLiteral(literal);
    }

    private InMemoryRateLimiter limiter(RateLimitPolicy policy) {
        return new InMemoryRateLimiter(policy, clock);
    }

    private static void assertAdmitted(RateLimitDecision decision) {
        assertThat(decision).isEqualTo(new Admitted());
    }

    private static void assertLimited(RateLimitDecision decision, Duration retryAfter) {
        assertThat(decision).isEqualTo(new Limited(retryAfter));
    }

    private static void fail(InMemoryRateLimiter limiter, ClientAddress client, int times) {
        IntStream.range(0, times).forEach(i -> limiter.recordFailure(client));
    }

    private void advanceSeconds(long seconds) {
        clock.advance(Duration.ofSeconds(seconds));
    }

    /** Nine failures, an admitted attempt and the tenth failure: restricted from now on. */
    private void restrict(InMemoryRateLimiter limiter, ClientAddress client) {
        fail(limiter, client, 9);
        assertAdmitted(limiter.tryAcquire(client));
        limiter.recordFailure(client);
    }

    // ---- the port: two separate dimensions ----

    @Test
    void requestsAndFailuresAreCountedApart() {
        InMemoryRateLimiter requestsFirst = limiter(ADMIN_LOGIN);
        for (int i = 0; i < 9; i++) {
            assertAdmitted(requestsFirst.tryAcquire(CLIENT));
        }
        fail(requestsFirst, CLIENT, 9);
        // Nine failures took nothing from the request quota: the tenth request is still admitted,
        // and only then is the quota exhausted.
        assertAdmitted(requestsFirst.tryAcquire(CLIENT));
        assertThat(requestsFirst.tryAcquire(CLIENT)).isInstanceOf(Limited.class);

        InMemoryRateLimiter failuresSecond = limiter(ADMIN_LOGIN);
        ClientAddress other = ip("203.0.113.10");
        for (int i = 0; i < 10; i++) {
            assertAdmitted(failuresSecond.tryAcquire(other));
        }
        fail(failuresSecond, other, 9);
        advanceSeconds(60);
        // Ten admitted requests are not ten failures: nine failures restrict nothing.
        assertAdmitted(failuresSecond.tryAcquire(other));
        assertAdmitted(failuresSecond.tryAcquire(other));
        failuresSecond.recordFailure(other);
        assertLimited(failuresSecond.tryAcquire(other), MINUTE);
    }

    @Test
    void aRejectedRequestIsNeverRecordedAndNeverMovesTheWindow() {
        InMemoryRateLimiter limiter = limiter(ADMIN_LOGIN);
        for (int i = 0; i < 10; i++) {
            assertAdmitted(limiter.tryAcquire(CLIENT));
        }
        for (int second = 1; second < 60; second++) {
            advanceSeconds(1);
            assertLimited(limiter.tryAcquire(CLIENT), Duration.ofSeconds(60 - second));
        }
        advanceSeconds(1);
        // 59 rejections later the first ten requests expire exactly when they would have.
        for (int i = 0; i < 10; i++) {
            assertAdmitted(limiter.tryAcquire(CLIENT));
        }
        assertLimited(limiter.tryAcquire(CLIENT), MINUTE);
    }

    // ---- layer 1: ten requests a minute ----

    @Test
    void theTenthRequestOfAMinuteIsAdmittedAndTheEleventhWaitsForTheFirstToExpire() {
        InMemoryRateLimiter limiter = limiter(ADMIN_LOGIN);
        for (int second = 0; second < 10; second++) {
            assertAdmitted(limiter.tryAcquire(CLIENT));
            advanceSeconds(1);
        }
        // t = 10 s: the request of t = 0 expires at t = 60 s.
        assertLimited(limiter.tryAcquire(CLIENT), Duration.ofSeconds(50));
    }

    @Test
    void theFirstRequestOfAnIpIsAdmitted() {
        assertAdmitted(limiter(ADMIN_LOGIN).tryAcquire(CLIENT));
    }

    @Test
    void theWindowSlidesRequestByRequest() {
        InMemoryRateLimiter limiter = limiter(ADMIN_LOGIN);
        for (int second = 0; second < 10; second++) {
            assertAdmitted(limiter.tryAcquire(CLIENT));
            if (second < 9) {
                advanceSeconds(1);
            }
        }
        advanceSeconds(51);
        // t = 60 s: the request of t = 0 has aged out and the one of t = 1 s has not.
        assertAdmitted(limiter.tryAcquire(CLIENT));
        assertLimited(limiter.tryAcquire(CLIENT), SECOND);
        advanceSeconds(1);
        assertAdmitted(limiter.tryAcquire(CLIENT));
    }

    @Test
    void anotherIpHasItsOwnCounter() {
        InMemoryRateLimiter limiter = limiter(ADMIN_LOGIN);
        for (int i = 0; i < 10; i++) {
            assertAdmitted(limiter.tryAcquire(CLIENT));
        }
        assertThat(limiter.tryAcquire(CLIENT)).isInstanceOf(Limited.class);

        assertAdmitted(limiter.tryAcquire(ip("203.0.113.10")));
    }

    @Test
    void retryAfterKeepsTheNanosecondsTheLimiterKnows() {
        InMemoryRateLimiter limiter = limiter(ADMIN_LOGIN);
        for (int i = 0; i < 10; i++) {
            assertAdmitted(limiter.tryAcquire(CLIENT));
        }
        clock.advance(Duration.ofMillis(59_800));

        RateLimitDecision decision = limiter.tryAcquire(CLIENT);

        assertLimited(decision, Duration.ofMillis(200));
        assertThat(((Limited) decision).retryAfterSeconds()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"1,1", "200000000,1", "999999999,1", "1000000000,1", "1000000001,2",
            "14500000000,15", "15000000000,15", "15000000001,16"})
    void retryAfterIsWholeSecondsRoundedUpAndNeverBelowOne(long nanos, long seconds) {
        assertThat(new Limited(Duration.ofNanos(nanos)).retryAfterSeconds()).isEqualTo(seconds);
    }

    @Test
    void aLimitedDecisionNeedsAPositiveWait() {
        assertThatThrownBy(() -> new Limited(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Limited(Duration.ofNanos(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- layer 2: back-off after ten failures ----

    @Test
    void theNinthFailureDoesNotRestrict() {
        InMemoryRateLimiter limiter = limiter(ADMIN_LOGIN);
        fail(limiter, CLIENT, 9);

        assertAdmitted(limiter.tryAcquire(CLIENT));
        advanceSeconds(10);
        assertAdmitted(limiter.tryAcquire(CLIENT));
    }

    @Test
    void theTenthFailureLimitsTheIpToOneAttemptAMinute() {
        InMemoryRateLimiter limiter = limiter(ADMIN_LOGIN);
        restrict(limiter, CLIENT);

        advanceSeconds(30);
        assertLimited(limiter.tryAcquire(CLIENT), Duration.ofSeconds(30));
        advanceSeconds(30);
        assertAdmitted(limiter.tryAcquire(CLIENT));
    }

    @Test
    void failuresOlderThanTheWindowDoNotCount() {
        InMemoryRateLimiter limiter = limiter(ADMIN_LOGIN);
        limiter.recordFailure(CLIENT);
        clock.advance(TEN_MINUTES.plusSeconds(1));
        fail(limiter, CLIENT, 9);

        assertAdmitted(limiter.tryAcquire(CLIENT));
        advanceSeconds(10);
        assertAdmitted(limiter.tryAcquire(CLIENT));
    }

    @Test
    void aFailureCountsOnlyWhileItsAgeIsStrictlyBelowTheWindow() {
        InMemoryRateLimiter atTheEdge = limiter(ADMIN_LOGIN);
        atTheEdge.recordFailure(CLIENT);
        clock.advance(TEN_MINUTES);
        fail(atTheEdge, CLIENT, 9);
        // The first failure is exactly ten minutes old: it no longer counts, nine do.
        assertAdmitted(atTheEdge.tryAcquire(CLIENT));
        advanceSeconds(10);
        assertAdmitted(atTheEdge.tryAcquire(CLIENT));

        MutableClock otherClock = new MutableClock(clock.instant());
        InMemoryRateLimiter justInside = new InMemoryRateLimiter(ADMIN_LOGIN, otherClock);
        ClientAddress other = ip("203.0.113.10");
        justInside.recordFailure(other);
        otherClock.advance(TEN_MINUTES.minus(NANO));
        fail(justInside, other, 9);
        // One nanosecond younger, the first failure still counts and ten restrict the IP.
        assertAdmitted(justInside.tryAcquire(other));
        otherClock.advance(Duration.ofSeconds(10));
        assertLimited(justInside.tryAcquire(other), Duration.ofSeconds(50));
    }

    @Test
    void aSuccessfulLoginNeverClearsTheFailureCount() {
        InMemoryRateLimiter limiter = limiter(ADMIN_LOGIN);
        fail(limiter, CLIENT, 9);
        for (int success = 0; success < 3; success++) {
            advanceSeconds(1);
            assertAdmitted(limiter.tryAcquire(CLIENT));
        }

        limiter.recordFailure(CLIENT);

        // Three successes later, the tenth failure of the window still restricts the IP.
        assertLimited(limiter.tryAcquire(CLIENT), MINUTE);
    }

    @Test
    void theRestrictionEndsAtTheOneHourCapEvenIfTheIpKeepsFailing() {
        InMemoryRateLimiter limiter = limiter(ADMIN_LOGIN);
        restrict(limiter, CLIENT);
        for (int minute = 1; minute < 60; minute++) {
            clock.advance(MINUTE);
            assertAdmitted(limiter.tryAcquire(CLIENT));
            limiter.recordFailure(CLIENT);
        }

        clock.advance(MINUTE.minus(NANO));
        // One nanosecond before the cap the attempt of minute 59 still holds the IP back.
        assertLimited(limiter.tryAcquire(CLIENT), NANO);
        clock.advance(NANO);
        // At the cap the restriction is over: two attempts in the same instant are both admitted.
        assertAdmitted(limiter.tryAcquire(CLIENT));
        assertAdmitted(limiter.tryAcquire(CLIENT));
        // And the failure counter starts from zero: nine failures restrict nothing, although the
        // nine failures of the last ten minutes would have restricted the IP at the first new one.
        fail(limiter, CLIENT, 9);
        assertAdmitted(limiter.tryAcquire(CLIENT));
        assertAdmitted(limiter.tryAcquire(CLIENT));
    }

    @Test
    void tenMinutesWithoutAFailureEndTheRestrictionBeforeTheCap() {
        InMemoryRateLimiter limiter = limiter(ADMIN_LOGIN);
        restrict(limiter, CLIENT);

        clock.advance(TEN_MINUTES.minus(NANO));
        // The last failure is one nanosecond short of ten minutes old: still restricted.
        assertAdmitted(limiter.tryAcquire(CLIENT));
        assertLimited(limiter.tryAcquire(CLIENT), MINUTE);
        clock.advance(NANO);
        // Ten minutes exactly, no failure since: the restriction ends, far from the one-hour cap.
        assertAdmitted(limiter.tryAcquire(CLIENT));
        assertAdmitted(limiter.tryAcquire(CLIENT));
        // The failures stay recorded, but none is inside the window, so the next one counts one.
        limiter.recordFailure(CLIENT);
        assertAdmitted(limiter.tryAcquire(CLIENT));
    }

    @Test
    void theRestrictionIsNotLiftedWhileTheIpKeepsFailing() {
        InMemoryRateLimiter limiter = limiter(ADMIN_LOGIN);
        restrict(limiter, CLIENT);

        for (int minute = 1; minute <= 12; minute++) {
            clock.advance(MINUTE);
            assertAdmitted(limiter.tryAcquire(CLIENT));
            // From minute 10 on, the ten failures of t = 0 are out of the window and fewer than ten
            // failures are inside it, yet the IP that keeps failing stays at one attempt a minute.
            assertLimited(limiter.tryAcquire(CLIENT), MINUTE);
            limiter.recordFailure(CLIENT);
        }
    }

    @Test
    void whenBothLayersRejectTheRetryAfterIsTheLongerWait() {
        InMemoryRateLimiter layerTwoLonger = limiter(ADMIN_LOGIN);
        for (int second = 0; second < 10; second++) {
            assertAdmitted(layerTwoLonger.tryAcquire(CLIENT));
            advanceSeconds(1);
        }
        fail(layerTwoLonger, CLIENT, 10);
        // t = 10 s. Layer 1 waits 50 s for the request of t = 0, layer 2 waits 59 s for the
        // attempt of t = 9 s.
        assertLimited(layerTwoLonger.tryAcquire(CLIENT), Duration.ofSeconds(59));

        MutableClock otherClock = new MutableClock(clock.instant());
        InMemoryRateLimiter layerOneLonger = new InMemoryRateLimiter(new RateLimitPolicy(
                2, MINUTE, 2, TEN_MINUTES, Duration.ofSeconds(10), HOUR, 100), otherClock);
        ClientAddress other = ip("203.0.113.10");
        assertAdmitted(layerOneLonger.tryAcquire(other));
        otherClock.advance(Duration.ofSeconds(5));
        assertAdmitted(layerOneLonger.tryAcquire(other));
        otherClock.advance(SECOND);
        fail(layerOneLonger, other, 2);
        // t = 6 s. Layer 1 waits 54 s, layer 2 only 9 s.
        assertLimited(layerOneLonger.tryAcquire(other), Duration.ofSeconds(54));
    }

    @Test
    void aLimitAndAThresholdLargerThanTheFirstAllocationAreCountedExactly() {
        InMemoryRateLimiter limiter = limiter(new RateLimitPolicy(100, Duration.ofSeconds(200), 40,
                TEN_MINUTES, MINUTE, HOUR, 10));
        for (int second = 0; second < 100; second++) {
            assertAdmitted(limiter.tryAcquire(CLIENT));
            advanceSeconds(1);
        }
        // t = 100 s: a hundred requests are inside the window and the oldest leaves at t = 200 s.
        assertLimited(limiter.tryAcquire(CLIENT), Duration.ofSeconds(100));
        advanceSeconds(100);
        assertAdmitted(limiter.tryAcquire(CLIENT));
        assertLimited(limiter.tryAcquire(CLIENT), SECOND);

        ClientAddress other = ip("203.0.113.10");
        fail(limiter, other, 39);
        assertAdmitted(limiter.tryAcquire(other));
        advanceSeconds(10);
        assertAdmitted(limiter.tryAcquire(other));
        limiter.recordFailure(other);
        assertLimited(limiter.tryAcquire(other), MINUTE);
    }

    // ---- IPv6 keys ----

    @Test
    void twoAddressesOfOneSlash64ShareACounterAndTwoSlash64sDoNot() {
        InMemoryRateLimiter limiter = limiter(ADMIN_LOGIN);
        for (int i = 0; i < 6; i++) {
            assertAdmitted(limiter.tryAcquire(ip("2001:db8:1:2::1")));
        }
        for (int i = 0; i < 4; i++) {
            assertAdmitted(limiter.tryAcquire(ip("2001:db8:1:2:ffff:ffff:ffff:ffff")));
        }
        assertThat(limiter.tryAcquire(ip("2001:db8:1:2::9"))).isInstanceOf(Limited.class);
        assertAdmitted(limiter.tryAcquire(ip("2001:db8:1:3::1")));
    }

    @Test
    void anIpv4MappedAddressSharesTheCounterOfTheIpv4ItContains() {
        InMemoryRateLimiter limiter = limiter(ADMIN_LOGIN);
        for (int i = 0; i < 5; i++) {
            assertAdmitted(limiter.tryAcquire(ip("::ffff:203.0.113.9")));
            assertAdmitted(limiter.tryAcquire(CLIENT));
        }
        assertThat(limiter.tryAcquire(CLIENT)).isInstanceOf(Limited.class);
    }

    /**
     * Decision S-3 of the review of {@link ClientAddress}: a NAT64 address and an IPv4-compatible
     * address are IPv6 addresses to the key, so every one of a /64 shares one counter. Accepted and
     * recorded rather than normalized: neither is routable as the source of a connection, so the
     * only way to meet one is a header written by a client, and the merge makes the limit stricter,
     * never looser. This test documents the behavior so that a change to it is a decision.
     */
    @Test
    void nat64AndIpv4CompatibleAddressesAreAcceptedAsOneSharedSlash64() {
        InMemoryRateLimiter limiter = limiter(ADMIN_LOGIN);
        for (int i = 0; i < 5; i++) {
            assertAdmitted(limiter.tryAcquire(ip("64:ff9b::203.0.113.9")));
            assertAdmitted(limiter.tryAcquire(ip("64:ff9b::203.0.113.10")));
        }
        assertThat(limiter.tryAcquire(ip("64:ff9b::198.51.100.1"))).isInstanceOf(Limited.class);

        for (int i = 0; i < 10; i++) {
            assertAdmitted(limiter.tryAcquire(ip("::203.0.113.9")));
        }
        assertThat(limiter.tryAcquire(ip("::198.51.100.1"))).isInstanceOf(Limited.class);
    }

    @ParameterizedTest
    @MethodSource("nonPositiveValues")
    void aNonPositiveValueIsRejectedNamingTheField(String field, long value) {
        assertThatThrownBy(() -> policyWith(field, value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(field)
                .hasMessageContaining("positive");
    }

    @Test
    void aDurationBeyondWhatNanosecondsCanHoldIsRejectedNamingTheField() {
        assertThatThrownBy(() -> new RateLimitPolicy(10, Duration.ofDays(200_000), 10, TEN_MINUTES,
                MINUTE, HOUR, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestWindow");
    }

    @Test
    void aMissingDurationIsRejectedNamingTheField() {
        assertThatThrownBy(() -> new RateLimitPolicy(10, MINUTE, 10, null, MINUTE, HOUR, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureWindow");
    }

    @Test
    void thePolicyReportsTheValuesItWasBuiltWith() {
        assertThat(ADMIN_LOGIN.requestLimit()).isEqualTo(10);
        assertThat(ADMIN_LOGIN.requestWindow()).isEqualTo(MINUTE);
        assertThat(ADMIN_LOGIN.failureThreshold()).isEqualTo(10);
        assertThat(ADMIN_LOGIN.failureWindow()).isEqualTo(TEN_MINUTES);
        assertThat(ADMIN_LOGIN.restrictedInterval()).isEqualTo(MINUTE);
        assertThat(ADMIN_LOGIN.restrictionCap()).isEqualTo(HOUR);
        assertThat(ADMIN_LOGIN.maxEntries()).isEqualTo(1_000);
    }

    static Stream<Arguments> nonPositiveValues() {
        return Stream.of("requestLimit", "requestWindow", "failureThreshold", "failureWindow",
                "restrictedInterval", "restrictionCap", "maxEntries")
                .flatMap(field -> Stream.of(Arguments.of(field, 0L), Arguments.of(field, -1L)));
    }

    private static RateLimitPolicy policyWith(String field, long value) {
        Duration duration = Duration.ofSeconds(value);
        return switch (field) {
            case "requestLimit" -> new RateLimitPolicy((int) value, MINUTE, 10, TEN_MINUTES, MINUTE,
                    HOUR, 10);
            case "requestWindow" -> new RateLimitPolicy(10, duration, 10, TEN_MINUTES, MINUTE, HOUR,
                    10);
            case "failureThreshold" -> new RateLimitPolicy(10, MINUTE, (int) value, TEN_MINUTES,
                    MINUTE, HOUR, 10);
            case "failureWindow" -> new RateLimitPolicy(10, MINUTE, 10, duration, MINUTE, HOUR, 10);
            case "restrictedInterval" -> new RateLimitPolicy(10, MINUTE, 10, TEN_MINUTES, duration,
                    HOUR, 10);
            case "restrictionCap" -> new RateLimitPolicy(10, MINUTE, 10, TEN_MINUTES, MINUTE,
                    duration, 10);
            case "maxEntries" -> new RateLimitPolicy(10, MINUTE, 10, TEN_MINUTES, MINUTE, HOUR,
                    (int) value);
            default -> throw new IllegalArgumentException(field);
        };
    }

    // ---- the declared limitation of an in-memory limiter ----

    @Test
    void aNewInstanceKnowsNothingOfTheOldOneAsAfterARestart() {
        InMemoryRateLimiter beforeTheRestart = limiter(ADMIN_LOGIN);
        for (int i = 0; i < 10; i++) {
            assertAdmitted(beforeTheRestart.tryAcquire(CLIENT));
        }
        assertThat(beforeTheRestart.tryAcquire(CLIENT)).isInstanceOf(Limited.class);

        InMemoryRateLimiter afterTheRestart = limiter(ADMIN_LOGIN);

        // The state lives in this process and nowhere else: the declared limitation (the note of
        // docs/03-seguridad.md, §4.4 and §10, arrives with the edge, task 3.2).
        assertAdmitted(afterTheRestart.tryAcquire(CLIENT));
    }
}
