package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.security.RateLimitDecision.Admitted;
import com.confia.shared.security.RateLimitDecision.Limited;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * How {@link InMemoryRateLimiter} reads time (review I-1 and S-1 of the in-memory limiter): from a
 * monotonic nanosecond source whose value means nothing and whose differences mean everything, and
 * inside the lock of the client it is deciding about. A wall clock would let a step of the system
 * time unlock every client (forwards) or stop the sweep until the table fills (backwards).
 */
class InMemoryRateLimiterTimeTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
    private static final long SECOND = 1_000_000_000L;

    private static final RateLimitPolicy ADMIN_LOGIN =
            new RateLimitPolicy(10, MINUTE, 10, TEN_MINUTES, MINUTE, Duration.ofHours(1), 1_000);

    private static final Set<String> WALL_CLOCK_TYPES = Set.of("java.time.Clock",
            "java.time.Instant", "java.time.LocalDateTime", "java.time.ZonedDateTime",
            "java.time.OffsetDateTime", "java.util.Date");

    private static ClientAddress client(int n) {
        return ClientAddress.parseLiteral("198.51.100." + n);
    }

    /**
     * Counters that start at zero, below it, one second before the wrap past {@code Long.MAX_VALUE}
     * and five seconds before it, so that a window and a restriction cross the wrap while they run.
     */
    static Stream<Long> counterStarts() {
        return Stream.of(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE - 5 * SECOND,
                Long.MAX_VALUE - 45 * SECOND, Long.MAX_VALUE - 700 * SECOND);
    }

    // ---- no wall clock ----

    @Test
    void theLimiterDependsOnNoWallClockAndReadsNoWallTime() {
        List<Class<?>> classes = new ArrayList<>(List.of(InMemoryRateLimiter.class));
        classes.addAll(List.of(InMemoryRateLimiter.class.getDeclaredClasses()));
        assertThat(classes).hasSizeGreaterThan(3);

        Set<JavaClass> imported = new ClassFileImporter()
                .importClasses(classes.toArray(new Class<?>[0])).stream()
                .filter(javaClass -> javaClass.getName().startsWith(InMemoryRateLimiter.class.getName()))
                .collect(Collectors.toSet());
        assertThat(imported).hasSameSizeAs(classes);

        List<String> wallClockDependencies = imported.stream()
                .flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
                .map(Dependency::getTargetClass)
                .map(JavaClass::getName)
                .filter(WALL_CLOCK_TYPES::contains)
                .toList();
        List<String> wallClockCalls = imported.stream()
                .flatMap(javaClass -> javaClass.getMethodCallsFromSelf().stream())
                .map(InMemoryRateLimiterTimeTest::callTarget)
                .filter(target -> target.equals("java.lang.System.currentTimeMillis")
                        || target.startsWith("java.time.Instant.now")
                        || target.startsWith("java.time.Clock."))
                .toList();

        assertThat(wallClockDependencies).as("types of a wall clock").isEmpty();
        assertThat(wallClockCalls).as("calls that read the wall time").isEmpty();
    }

    private static String callTarget(JavaMethodCall call) {
        return call.getTargetOwner().getName() + "." + call.getName();
    }

    @ParameterizedTest
    @MethodSource("counterStarts")
    void theLayersDecideOnDifferencesWhereverTheCounterStartsAndWhenItWraps(long start) {
        MutableClock time = new MutableClock(start);
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(ADMIN_LOGIN, time);
        ClientAddress requests = client(1);
        ClientAddress failures = client(2);

        for (int second = 0; second < 10; second++) {
            assertThat(limiter.tryAcquire(requests)).isEqualTo(new Admitted());
            time.advance(Duration.ofSeconds(1));
        }
        // Layer 1: the request of the first second leaves the window 50 seconds from now.
        assertThat(limiter.tryAcquire(requests)).isEqualTo(new Limited(Duration.ofSeconds(50)));

        for (int i = 0; i < 9; i++) {
            limiter.recordFailure(failures);
        }
        assertThat(limiter.tryAcquire(failures)).isEqualTo(new Admitted());
        limiter.recordFailure(failures);
        time.advance(Duration.ofSeconds(30));
        // Layer 2: restricted by the tenth failure, to one attempt a minute.
        assertThat(limiter.tryAcquire(failures)).isEqualTo(new Limited(Duration.ofSeconds(30)));
        time.advance(Duration.ofSeconds(30));
        assertThat(limiter.tryAcquire(failures)).isEqualTo(new Admitted());

        time.advance(TEN_MINUTES);
        // Ten quiet minutes end the restriction: two attempts of the same instant both enter.
        assertThat(limiter.tryAcquire(failures)).isEqualTo(new Admitted());
        assertThat(limiter.tryAcquire(failures)).isEqualTo(new Admitted());
    }

    @ParameterizedTest
    @MethodSource("counterStarts")
    void theSweepRecoversRoomAcrossTheWrapOfTheCounter(long start) {
        MutableClock time = new MutableClock(start);
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(
                new RateLimitPolicy(2, Duration.ofSeconds(10), 2, Duration.ofSeconds(20),
                        Duration.ofSeconds(5), MINUTE, 1), time);
        assertThat(limiter.tryAcquire(client(1))).isEqualTo(new Admitted());
        // The table is full: the sweep of this instant finds the entry live, and the next one is
        // not due before one second has passed.
        assertThat(limiter.tryAcquire(client(2)))
                .isEqualTo(new RateLimitDecision.CapacityExhausted());

        time.advance(Duration.ofSeconds(15));

        assertThat(limiter.tryAcquire(client(2))).isEqualTo(new Admitted());
        assertThat(limiter.size()).isEqualTo(1);
    }

    @Test
    void theProductionConstructorCountsWithTheJvmMonotonicCounter() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(new RateLimitPolicy(2,
                Duration.ofHours(1), 2, TEN_MINUTES, MINUTE, Duration.ofHours(1), 10));
        ClientAddress client = client(1);

        assertThat(limiter.tryAcquire(client)).isEqualTo(new Admitted());
        assertThat(limiter.tryAcquire(client)).isEqualTo(new Admitted());

        RateLimitDecision third = limiter.tryAcquire(client);
        assertThat(third).isInstanceOf(Limited.class);
        assertThat(((Limited) third).retryAfterSeconds()).isBetween(1L, 3_600L);
    }

    // ---- the time of a request is read inside the lock of its client (S-1) ----

    /**
     * A slow thread reads the time and is then held, between reading it and deciding, until a fast
     * thread that reads a later time has either finished or is blocked behind it. The limit is one
     * request a minute, so the order in which the two timestamps reach the entry is visible in the
     * answers. Read inside the lock of the client, the slow thread's earlier time is also the first
     * to be recorded: it is admitted and the later one waits. Read before the lock, the fast thread
     * records its later time first and the slow thread's earlier one arrives after it, which gives
     * the opposite answers and leaves the ring of the entry out of order.
     */
    @Test
    void aTimestampIsReadInsideTheLockOfItsClientSoTheRingStaysInOrder() throws Exception {
        MutableClock time = new MutableClock();
        AtomicReference<Thread> slow = new AtomicReference<>();
        AtomicReference<Thread> fast = new AtomicReference<>();
        AtomicBoolean slowHasRead = new AtomicBoolean();
        LongSupplier source = () -> {
            long read = time.getAsLong();
            if (Thread.currentThread() == slow.get()) {
                slowHasRead.set(true);
                holdUntilBlockedOrDone(fast);
            }
            return read;
        };
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(new RateLimitPolicy(1, MINUTE, 5,
                TEN_MINUTES, MINUTE, Duration.ofHours(1), 10), source);
        ClientAddress client = client(1);
        AtomicReference<RateLimitDecision> slowDecision = new AtomicReference<>();
        AtomicReference<RateLimitDecision> fastDecision = new AtomicReference<>();

        Thread slowThread = new Thread(() -> slowDecision.set(limiter.tryAcquire(client)));
        slow.set(slowThread);
        slowThread.start();
        awaitUntil(slowHasRead::get);
        time.advance(Duration.ofSeconds(10));
        Thread fastThread = new Thread(() -> fastDecision.set(limiter.tryAcquire(client)));
        fast.set(fastThread);
        fastThread.start();
        slowThread.join(30_000);
        fastThread.join(30_000);

        assertThat(slowDecision.get()).as("the earlier time is recorded first")
                .isEqualTo(new Admitted());
        assertThat(fastDecision.get()).as("the later time finds it and waits")
                .isEqualTo(new Limited(Duration.ofSeconds(50)));
    }

    private static void holdUntilBlockedOrDone(AtomicReference<Thread> other) {
        awaitUntil(() -> other.get() != null);
        Thread thread = other.get();
        awaitUntil(() -> thread.getState() == Thread.State.BLOCKED
                || thread.getState() == Thread.State.TERMINATED);
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + 10 * SECOND;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("the condition never held");
            }
            Thread.onSpinWait();
        }
    }
}
