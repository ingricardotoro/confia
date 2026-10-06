package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.security.RateLimitDecision.Admitted;
import com.confia.shared.security.RateLimitDecision.CapacityExhausted;
import com.confia.shared.security.RateLimitDecision.Limited;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.statistics.Statistics;

/**
 * {@link InMemoryRateLimiter} against a reference that is as naive as a reference can be
 * (web-edge-foundations design.md, decisions 15 and 16): it keeps every admission and every failure
 * in an unbounded list, never drops one, and recomputes each answer from the lists alone. The
 * limiter keeps two small rings and a restriction timestamp; the two must still give the same
 * decision, and the same {@code Retry-After} to the nanosecond, over random sequences of "move the
 * clock", {@code tryAcquire} and {@code recordFailure}. The reference counts in nanoseconds from
 * zero and never reads the limiter, the clock or a key.
 */
class InMemoryRateLimiterPropertiesTest {

    private static final long SECOND = 1_000_000_000L;

    /** Small values, so that a random sequence reaches every layer and every boundary. */
    private static final RateLimitPolicy POLICY = new RateLimitPolicy(3, Duration.ofSeconds(10), 3,
            Duration.ofSeconds(30), Duration.ofSeconds(20), Duration.ofSeconds(100), 1_000);

    /** Addresses 1 and 2 share a /64, so they are one client; every other address is its own. */
    private static final String[] ADDRESSES =
            {"203.0.113.9", "2001:db8:1:2::1", "2001:db8:1:2::2", "2001:db8:1:3::1"};
    private static final int[] CLIENT_OF_ADDRESS = {0, 1, 1, 2};

    private static final int TABLE_SIZE = 2;
    private static final RateLimitPolicy SMALL_TABLE = new RateLimitPolicy(3, Duration.ofSeconds(10),
            3, Duration.ofSeconds(30), Duration.ofSeconds(20), Duration.ofSeconds(100), TABLE_SIZE);

    /** A failed login is an attempt and, when it was admitted, the failure that follows it. */
    enum Kind { ACQUIRE, FAIL, FAILED_LOGIN }

    record Operation(long advanceNanos, int address, Kind kind) {}

    @Property(tries = 400)
    void theLimiterDecidesLikeTheNaiveReferenceAtEveryInstant(
            @ForAll("operations") List<Operation> operations) {
        MutableClock clock = new MutableClock();
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(POLICY, clock);
        NaiveReference reference = new NaiveReference(POLICY);
        long now = 0;

        for (Operation operation : operations) {
            clock.advance(Duration.ofNanos(operation.advanceNanos()));
            now += operation.advanceNanos();
            ClientAddress address = ClientAddress.parseLiteral(ADDRESSES[operation.address()]);
            int client = CLIENT_OF_ADDRESS[operation.address()];
            if (operation.kind() == Kind.FAIL) {
                limiter.recordFailure(address);
                reference.recordFailure(client, now);
                Statistics.collect("failure");
                continue;
            }
            boolean restricted = reference.isRestricted(client);
            RateLimitDecision expected = reference.tryAcquire(client, now);
            assertThat(limiter.tryAcquire(address)).isEqualTo(expected);
            Statistics.collect(label(expected, restricted));
            if (operation.kind() == Kind.FAILED_LOGIN && expected instanceof Admitted) {
                limiter.recordFailure(address);
                reference.recordFailure(client, now);
            }
        }
        Statistics.coverage(coverage -> {
            coverage.check("failure").count(count -> count > 0);
            coverage.check("admitted").count(count -> count > 0);
            coverage.check("limited").count(count -> count > 0);
            coverage.check("limited while restricted").count(count -> count > 0);
        });
    }

    private static String label(RateLimitDecision decision, boolean restricted) {
        if (decision instanceof Admitted) {
            return "admitted";
        }
        return restricted ? "limited while restricted" : "limited";
    }

    @Property(tries = 400)
    void theTableNeverHoldsMoreThanItsSizeAndRefusesOnlyWhenItIsFull(
            @ForAll("tableOperations") List<Operation> operations) {
        MutableClock clock = new MutableClock();
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(SMALL_TABLE, clock);

        for (Operation operation : operations) {
            clock.advance(Duration.ofNanos(operation.advanceNanos()));
            ClientAddress address = ClientAddress.parseLiteral("198.51.100." + operation.address());
            int sizeBefore = limiter.size();
            if (operation.kind() == Kind.FAIL) {
                limiter.recordFailure(address);
            } else {
                RateLimitDecision decision = limiter.tryAcquire(address);
                if (sizeBefore < TABLE_SIZE) {
                    assertThat(decision).isNotInstanceOf(CapacityExhausted.class);
                }
                if (decision instanceof CapacityExhausted) {
                    assertThat(limiter.size()).isEqualTo(TABLE_SIZE);
                    Statistics.collect("refused");
                } else {
                    Statistics.collect("answered");
                }
            }
            assertThat(limiter.size()).isLessThanOrEqualTo(TABLE_SIZE);
        }
        Statistics.coverage(coverage -> {
            coverage.check("refused").count(count -> count > 0);
            coverage.check("answered").count(count -> count > 0);
        });
    }

    @Property(tries = 500)
    void retryAfterIsTheWaitRoundedUpToWholeSecondsAndNeverBelowOne(
            @ForAll @LongRange(min = 1, max = 4_000_000_000_000L) long nanos) {
        // BigDecimal gives the ceiling without the integer arithmetic the production code uses.
        long expected = BigDecimal.valueOf(nanos).divide(BigDecimal.valueOf(SECOND), 0,
                RoundingMode.CEILING).longValueExact();

        assertThat(new Limited(Duration.ofNanos(nanos)).retryAfterSeconds())
                .isEqualTo(Math.max(1, expected));
    }

    @Provide
    Arbitrary<List<Operation>> operations() {
        return operationsOver(0, ADDRESSES.length - 1, 150);
    }

    @Provide
    Arbitrary<List<Operation>> tableOperations() {
        return operationsOver(1, 6, 100);
    }

    private static Arbitrary<List<Operation>> operationsOver(int firstAddress, int lastAddress,
            int maxLength) {
        // Half of the moves land exactly on or one nanosecond short of a boundary of the policy.
        Arbitrary<Long> boundaries = Arbitraries.of(0L, 1L, SECOND - 1, SECOND, 10 * SECOND - 1,
                10 * SECOND, 20 * SECOND - 1, 20 * SECOND, 20 * SECOND + 1, 30 * SECOND - 1,
                30 * SECOND, 100 * SECOND - 1, 100 * SECOND);
        Arbitrary<Long> advance = Arbitraries.frequencyOf(Tuple.of(1, boundaries),
                Tuple.of(1, Arbitraries.longs().between(0, 120 * SECOND)));
        Arbitrary<Kind> kind = Arbitraries.frequencyOf(Tuple.of(2, Arbitraries.just(Kind.ACQUIRE)),
                Tuple.of(1, Arbitraries.just(Kind.FAIL)),
                Tuple.of(3, Arbitraries.just(Kind.FAILED_LOGIN)));
        return Combinators.combine(advance, Arbitraries.integers().between(firstAddress, lastAddress),
                kind).as(Operation::new).list().ofMinSize(1).ofMaxSize(maxLength);
    }

    /** Every admission and failure ever seen, per client, and the rules of the design on top. */
    private static final class NaiveReference {

        private final RateLimitPolicy policy;
        private final Map<Integer, Client> clients = new HashMap<>();

        NaiveReference(RateLimitPolicy policy) {
            this.policy = policy;
        }

        boolean isRestricted(int client) {
            return client(client).restrictedSince != null;
        }

        RateLimitDecision tryAcquire(int id, long now) {
            Client client = client(id);
            settle(client, now);
            long window = policy.requestWindow().toNanos();
            long interval = policy.restrictedInterval().toNanos();
            List<Long> live = client.admissions.stream().filter(at -> now - at < window).toList();
            long wait = 0;
            if (live.size() >= policy.requestLimit()) {
                wait = window - (now - Collections.min(live));
            }
            if (client.restrictedSince != null && client.lastAdmitted != null
                    && now - client.lastAdmitted < interval) {
                wait = Math.max(wait, interval - (now - client.lastAdmitted));
            }
            if (wait > 0) {
                return new Limited(Duration.ofNanos(wait));
            }
            client.admissions.add(now);
            client.lastAdmitted = now;
            return new Admitted();
        }

        void recordFailure(int id, long now) {
            Client client = client(id);
            settle(client, now);
            client.failures.add(now);
            long window = policy.failureWindow().toNanos();
            long inside = client.failures.stream().filter(at -> now - at < window).count();
            if (client.restrictedSince == null && inside >= policy.failureThreshold()) {
                client.restrictedSince = now;
            }
        }

        /** The two ways a restriction ends: the cap, which resets the failures, or a quiet window. */
        private void settle(Client client, long now) {
            long window = policy.failureWindow().toNanos();
            if (client.restrictedSince != null
                    && now - client.restrictedSince >= policy.restrictionCap().toNanos()) {
                client.restrictedSince = null;
                client.failures = new ArrayList<>();
            }
            if (client.restrictedSince != null
                    && client.failures.stream().noneMatch(at -> now - at < window)) {
                client.restrictedSince = null;
            }
        }

        private Client client(int id) {
            return clients.computeIfAbsent(id, key -> new Client());
        }

        private static final class Client {
            final List<Long> admissions = new ArrayList<>();
            List<Long> failures = new ArrayList<>();
            Long restrictedSince;
            Long lastAdmitted;
        }
    }
}
