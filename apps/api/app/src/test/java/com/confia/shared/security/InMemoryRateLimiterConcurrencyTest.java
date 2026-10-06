package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.security.RateLimitDecision.Admitted;
import com.confia.shared.security.RateLimitDecision.CapacityExhausted;
import com.confia.shared.security.RateLimitDecision.Limited;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import org.junit.jupiter.api.RepeatedTest;

/**
 * {@link InMemoryRateLimiter} under real contention (specs/web-edge, "Exactitud bajo concurrencia"
 * and "Llenado concurrente"). Every thread waits on one {@link CyclicBarrier} and the clock is a
 * {@link MutableClock} nobody moves while they run, so the answers are exact counts and never a
 * race against the wall clock. Each scenario repeats twenty times because a lost update would show
 * as a count that is wrong only some of the time.
 */
class InMemoryRateLimiterConcurrencyTest {

    private static final int REPETITIONS = 20;
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final int TABLE_SIZE = 50;

    private static final RateLimitPolicy ADMIN_LOGIN = new RateLimitPolicy(10, MINUTE, 10,
            Duration.ofMinutes(10), MINUTE, Duration.ofHours(1), 1_000);

    private final MutableClock clock = new MutableClock();

    private static ClientAddress client(int n) {
        return ClientAddress.parseLiteral("10.0." + (n / 250) + "." + (n % 250 + 1));
    }

    private static <T> List<T> runTogether(int threads, IntFunction<T> task) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int number = i;
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return task.apply(number);
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private static long count(List<RateLimitDecision> decisions, Class<?> type) {
        return decisions.stream().filter(type::isInstance).count();
    }

    @RepeatedTest(REPETITIONS)
    void fiftyThreadsOnOneIpAdmitExactlyTenAndLimitExactlyForty() throws Exception {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(ADMIN_LOGIN, clock);
        ClientAddress client = client(1);

        List<RateLimitDecision> first = runTogether(50, number -> limiter.tryAcquire(client));

        assertThat(count(first, Admitted.class)).isEqualTo(10);
        assertThat(count(first, Limited.class)).isEqualTo(40);

        clock.advance(MINUTE);
        List<RateLimitDecision> second = runTogether(50, number -> limiter.tryAcquire(client));

        // Forty rejections moved nothing: the window ends when it would have, and ten more enter.
        assertThat(count(second, Admitted.class)).isEqualTo(10);
        assertThat(count(second, Limited.class)).isEqualTo(40);
    }

    @RepeatedTest(REPETITIONS)
    void exactlyTenConcurrentFailuresRestrictTheIpAndNineDoNot() throws Exception {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(ADMIN_LOGIN, clock);
        ClientAddress nine = client(1);
        ClientAddress ten = client(2);

        runTogether(9, number -> {
            limiter.recordFailure(nine);
            return number;
        });
        runTogether(10, number -> {
            limiter.recordFailure(ten);
            return number;
        });

        assertThat(limiter.tryAcquire(nine)).isEqualTo(new Admitted());
        assertThat(limiter.tryAcquire(nine)).isEqualTo(new Admitted());
        assertThat(limiter.tryAcquire(ten)).isEqualTo(new Admitted());
        assertThat(limiter.tryAcquire(ten)).isEqualTo(new Limited(MINUTE));
    }

    @RepeatedTest(REPETITIONS)
    void aTableOfNHoldingNoOneAdmitsExactlyNOfTwoNIpsAndNeverReservesPastN() throws Exception {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(tableOf(TABLE_SIZE), clock);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicInteger largest = new AtomicInteger();
        AtomicLong samples = new AtomicLong();
        Thread sampler = startSampler(limiter, finished, largest, samples);
        long before = samples.get();

        List<RateLimitDecision> decisions;
        try {
            decisions = runTogether(2 * TABLE_SIZE, number -> limiter.tryAcquire(client(number)));
        } finally {
            finished.set(true);
            sampler.join();
        }

        assertThat(count(decisions, Admitted.class)).isEqualTo(TABLE_SIZE);
        assertThat(count(decisions, CapacityExhausted.class)).isEqualTo(TABLE_SIZE);
        assertSampled(samples, before);
        assertThat(largest.get()).isLessThanOrEqualTo(TABLE_SIZE);
        assertAtRest(limiter, TABLE_SIZE);
    }

    @RepeatedTest(REPETITIONS)
    void sweepingExpiredEntriesWhileNewIpsArriveNeverReservesPastTheTable() throws Exception {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(tableOf(TABLE_SIZE), clock);
        for (int n = 0; n < TABLE_SIZE; n++) {
            assertThat(limiter.tryAcquire(client(n))).isEqualTo(new Admitted());
        }
        clock.advance(MINUTE.plusSeconds(1));
        AtomicBoolean finished = new AtomicBoolean();
        AtomicInteger largest = new AtomicInteger();
        AtomicLong samples = new AtomicLong();
        Thread sampler = startSampler(limiter, finished, largest, samples);
        long before = samples.get();

        List<RateLimitDecision> decisions;
        try {
            decisions = runTogether(2 * TABLE_SIZE,
                    number -> limiter.tryAcquire(client(TABLE_SIZE + number)));
        } finally {
            finished.set(true);
            sampler.join();
        }

        long admitted = count(decisions, Admitted.class);
        // Only the thread that wins the sweep retries, so how many enter is not fixed: at least
        // one, at most N, and every other answer is a refusal. The old entries are all gone.
        assertThat(admitted).isBetween(1L, (long) TABLE_SIZE);
        assertThat(count(decisions, CapacityExhausted.class)).isEqualTo(2L * TABLE_SIZE - admitted);
        assertSampled(samples, before);
        assertThat(largest.get()).isLessThanOrEqualTo(TABLE_SIZE);
        assertAtRest(limiter, (int) admitted);
    }

    /**
     * Samples the counter of reserved slots, not {@code ConcurrentHashMap.size()}: the counter is a
     * single atomic value, so the bound it reports is a real instant, while the map adds up its
     * bins one after another and may total a table that never existed while entries come and go
     * (review I-3). What the samples bound is therefore the <b>reservations</b>, which a
     * non-atomic reservation would push past N; that the map itself never holds N + 1 entries for an
     * instant (release only after removal, see {@code InMemoryRateLimiter#reclaim}) is left
     * unsampled on purpose, because no snapshot of the map exists (review of 3.1c).
     *
     * <p>It returns only once the first sample has been taken, so the workers always start against a
     * running sampler, and {@link #assertSampled} makes a test fail if no sample was ever taken
     * instead of passing on a largest value that stayed 0 (review of 3.1c).
     */
    private static Thread startSampler(InMemoryRateLimiter limiter, AtomicBoolean finished,
            AtomicInteger largest, AtomicLong samples) throws InterruptedException {
        CountDownLatch firstSample = new CountDownLatch(1);
        Thread sampler = Thread.ofPlatform().start(() -> {
            do {
                largest.accumulateAndGet(limiter.reservedForTest(), Math::max);
                samples.incrementAndGet();
                firstSample.countDown();
                Thread.onSpinWait();
            } while (!finished.get());
        });
        assertThat(firstSample.await(30, TimeUnit.SECONDS)).as("the sampler started").isTrue();
        return sampler;
    }

    /** A sampled bound only means something if sampling went on while the workers ran. */
    private static void assertSampled(AtomicLong samples, long before) {
        assertThat(samples.get()).as("samples taken while the workers ran").isGreaterThan(before);
    }

    /** At rest nobody is inserting or removing, so the map and the counter must agree exactly. */
    private static void assertAtRest(InMemoryRateLimiter limiter, int expected) {
        assertThat(limiter.reservedForTest()).isEqualTo(expected);
        assertThat(limiter.size()).isEqualTo(expected);
    }

    private static RateLimitPolicy tableOf(int size) {
        return new RateLimitPolicy(10, MINUTE, 10, Duration.ofMinutes(10), MINUTE,
                Duration.ofHours(1), size);
    }
}
