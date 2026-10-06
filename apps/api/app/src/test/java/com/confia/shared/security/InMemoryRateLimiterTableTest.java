package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.security.RateLimitDecision.Admitted;
import com.confia.shared.security.RateLimitDecision.CapacityExhausted;
import com.confia.shared.security.RateLimitDecision.Limited;
import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The bounded table of {@link InMemoryRateLimiter}: it fails closed when full, it never evicts a
 * live entry and it recovers room only from entries whose forgetting changes no answer
 * (web-edge-foundations design.md, decision 16; specs/web-edge, "La tabla acotada falla cerrada al
 * llenarse"). The request and failure layers have their own class. Time is a {@link MutableClock}:
 * nothing here sleeps.
 */
class InMemoryRateLimiterTableTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration NANO = Duration.ofNanos(1);

    private final MutableClock clock = new MutableClock();

    /** Two requests per ten seconds, a restricted interval of five and a table of {@code size}. */
    private static RateLimitPolicy tableOf(int size) {
        return new RateLimitPolicy(2, Duration.ofSeconds(10), 2, Duration.ofSeconds(20),
                Duration.ofSeconds(5), MINUTE, size);
    }

    private static ClientAddress client(int n) {
        return ClientAddress.parseLiteral("198.51.100." + n);
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

    private static void assertExhausted(RateLimitDecision decision) {
        assertThat(decision).isEqualTo(new CapacityExhausted());
    }

    private static void fail(InMemoryRateLimiter limiter, ClientAddress client, int times) {
        IntStream.range(0, times).forEach(i -> limiter.recordFailure(client));
    }

    private void advanceSeconds(long seconds) {
        clock.advance(Duration.ofSeconds(seconds));
    }

    @Test
    void theTableAdmitsExactlyUpToItsSizeAndNoMore() {
        InMemoryRateLimiter limiter = limiter(tableOf(3));

        assertAdmitted(limiter.tryAcquire(client(1)));
        assertAdmitted(limiter.tryAcquire(client(2)));
        // N - 1 entries are live: the next new IP takes the last slot, the one after is refused.
        assertAdmitted(limiter.tryAcquire(client(3)));
        assertExhausted(limiter.tryAcquire(client(4)));
        assertExhausted(limiter.tryAcquire(client(5)));
        assertThat(limiter.size()).isEqualTo(3);
    }

    @Test
    void aFullTableKeepsCountingAndLimitingTheIpsItHolds() {
        InMemoryRateLimiter limiter = limiter(tableOf(2));
        assertAdmitted(limiter.tryAcquire(client(1)));
        assertAdmitted(limiter.tryAcquire(client(2)));
        assertExhausted(limiter.tryAcquire(client(3)));

        assertAdmitted(limiter.tryAcquire(client(1)));
        assertLimited(limiter.tryAcquire(client(1)), Duration.ofSeconds(10));
        assertExhausted(limiter.tryAcquire(client(3)));
    }

    @Test
    void anExpiredEntryMakesRoomForANewIp() {
        InMemoryRateLimiter limiter = limiter(tableOf(2));
        assertAdmitted(limiter.tryAcquire(client(1)));
        assertAdmitted(limiter.tryAcquire(client(2)));
        assertExhausted(limiter.tryAcquire(client(3)));

        advanceSeconds(15);

        assertAdmitted(limiter.tryAcquire(client(3)));
        assertThat(limiter.size()).isEqualTo(1);
    }

    @Test
    void theTableIsSweptAtMostOnceASecond() {
        InMemoryRateLimiter limiter = limiter(tableOf(1));
        assertAdmitted(limiter.tryAcquire(client(1)));
        clock.advance(Duration.ofMillis(9_500));
        assertExhausted(limiter.tryAcquire(client(2)));
        // The sweep of t = 9.5 s found the entry live. It expires at t = 10 s, but the next sweep
        // is not due before t = 10.5 s.
        clock.advance(Duration.ofMillis(700));
        assertExhausted(limiter.tryAcquire(client(2)));
        clock.advance(Duration.ofMillis(400));
        assertAdmitted(limiter.tryAcquire(client(2)));
    }

    @Test
    void anEntryIsReclaimedExactlyWhenItsLastRequestLeavesTheWindow() {
        InMemoryRateLimiter oneNanoEarly = limiter(tableOf(1));
        assertAdmitted(oneNanoEarly.tryAcquire(client(1)));
        clock.advance(Duration.ofSeconds(10).minus(NANO));
        assertExhausted(oneNanoEarly.tryAcquire(client(2)));

        MutableClock otherClock = new MutableClock(clock.getAsLong());
        InMemoryRateLimiter onTheEdge = new InMemoryRateLimiter(tableOf(1), otherClock);
        assertAdmitted(onTheEdge.tryAcquire(client(1)));
        otherClock.advance(Duration.ofSeconds(10));
        // The request is exactly as old as the window: it no longer counts, and the entry goes.
        assertAdmitted(onTheEdge.tryAcquire(client(2)));
    }

    @Test
    void anEntryIsReclaimedExactlyWhenItsLastFailureLeavesTheWindow() {
        InMemoryRateLimiter oneNanoEarly = limiter(tableOf(1));
        oneNanoEarly.recordFailure(client(1));
        clock.advance(Duration.ofSeconds(20).minus(NANO));
        assertExhausted(oneNanoEarly.tryAcquire(client(2)));

        MutableClock otherClock = new MutableClock(clock.getAsLong());
        InMemoryRateLimiter onTheEdge = new InMemoryRateLimiter(tableOf(1), otherClock);
        onTheEdge.recordFailure(client(1));
        otherClock.advance(Duration.ofSeconds(20));
        assertAdmitted(onTheEdge.tryAcquire(client(2)));
    }

    @Test
    void anEntryHoldingAFailureInsideTheWindowIsNotReclaimed() {
        InMemoryRateLimiter limiter = limiter(tableOf(1));
        limiter.recordFailure(client(1));

        advanceSeconds(15);
        assertExhausted(limiter.tryAcquire(client(2)));
        advanceSeconds(6);

        assertAdmitted(limiter.tryAcquire(client(2)));
    }

    @Test
    void anEntryKeptAliveOnlyByARestrictionIsNotReclaimed() {
        InMemoryRateLimiter limiter = limiter(tableOf(1));
        assertAdmitted(limiter.tryAcquire(client(1)));
        fail(limiter, client(1), 2);

        advanceSeconds(15);
        // The request has expired and so has the interval, but the restriction is still on.
        assertExhausted(limiter.tryAcquire(client(2)));
        assertAdmitted(limiter.tryAcquire(client(1)));
        assertLimited(limiter.tryAcquire(client(1)), Duration.ofSeconds(5));
    }

    @Test
    void anEntryWhoseRestrictedIntervalIsStillRunningIsNotReclaimed() {
        InMemoryRateLimiter limiter = limiter(new RateLimitPolicy(2, Duration.ofSeconds(10), 2,
                Duration.ofSeconds(20), Duration.ofSeconds(30), MINUTE, 1));
        assertAdmitted(limiter.tryAcquire(client(1)));

        advanceSeconds(15);
        // Its request has expired but a restriction opened now would still owe 15 more seconds.
        assertExhausted(limiter.tryAcquire(client(2)));
        advanceSeconds(20);

        assertAdmitted(limiter.tryAcquire(client(2)));
    }

    @Test
    void aFailureOfAnIpTheFullTableCannotHoldIsDroppedAndNeverEvictsAnEntry() {
        InMemoryRateLimiter limiter = limiter(tableOf(1));
        assertAdmitted(limiter.tryAcquire(client(1)));

        fail(limiter, client(2), 5);

        assertThat(limiter.size()).isEqualTo(1);
        assertAdmitted(limiter.tryAcquire(client(1)));
        assertLimited(limiter.tryAcquire(client(1)), Duration.ofSeconds(10));
        assertExhausted(limiter.tryAcquire(client(2)));
    }
}
