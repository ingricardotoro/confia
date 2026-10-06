package com.confia.shared.security;

import com.confia.shared.security.RateLimitDecision.Admitted;
import com.confia.shared.security.RateLimitDecision.CapacityExhausted;
import com.confia.shared.security.RateLimitDecision.Limited;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The in-memory {@link RateLimiter} (web-edge-foundations design.md, decisions 15 and 16).
 *
 * <p><b>Declared limitation.</b> The state lives in this process and nowhere else. It is lost when
 * the process restarts, and it is not shared between replicas: each replica counts on its own, so
 * a client that reaches two replicas gets the limit of each. This is an interim control for the
 * administrative login, and it is not equivalent to the shared state of Redis, which replaces it
 * behind the same port in change 11.
 *
 * <p><b>Layer 1</b> admits at most {@code requestLimit} requests per client within a sliding
 * window. <b>Layer 2</b> restricts a client to one admitted attempt per {@code restrictedInterval}
 * from its {@code failureThreshold}th failure within {@code failureWindow}. The restriction ends
 * at the first of two instants: {@code restrictionCap} after it began, when the failures start from
 * zero again; or {@code failureWindow} after the client's last failure, when the failures stay
 * recorded. It does not end merely because the failures inside the window fall below the
 * threshold while the client keeps failing: that would give an attacker who fails once a minute a
 * fresh burst of the first layer at every lapse. A rejection is never recorded and never moves a
 * window.
 *
 * <p><b>The table</b> is bounded by {@code maxEntries} and fails closed: a client without an entry
 * is refused with {@link CapacityExhausted} when the table is full, and a live entry is never
 * evicted to make room (an eviction would let a flood of new addresses wipe the counter of the one
 * being limited). Room comes back only from entries that expired, found by a sweep that runs lazily
 * when the table is full and at most once a second, so that an attacker facing a full table cannot
 * turn each new address into a walk of the whole map. There is no scheduled task. A failure of a
 * client the full table cannot hold is not recorded, because the port cannot refuse it; the same
 * client is refused at its next {@link #tryAcquire} while the table stays full.
 *
 * <p>Every operation on a client runs inside {@link ConcurrentHashMap#compute}, which is atomic
 * per key, so the mutable state of an entry needs no lock of its own. The slot of a new client is
 * reserved with a compare-and-set on a counter that moves only on a real insertion or removal, so
 * the table never holds more than {@code maxEntries} entries at any instant. Time comes from the
 * injected {@link Clock} and is counted in nanoseconds; a clock that steps backwards can only make
 * a client wait longer, never admit it early.
 */
public final class InMemoryRateLimiter implements RateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long SWEEP_INTERVAL_NANOS = NANOS_PER_SECOND;
    private static final RateLimitDecision ADMITTED = new Admitted();
    private static final RateLimitDecision EXHAUSTED = new CapacityExhausted();

    private final Clock clock;
    private final Limits limits;
    private final int maxEntries;
    private final ConcurrentHashMap<ClientKey, Entry> table = new ConcurrentHashMap<>();
    private final AtomicInteger reserved = new AtomicInteger();
    private final AtomicLong nextSweepAt = new AtomicLong(Long.MIN_VALUE);

    public InMemoryRateLimiter(RateLimitPolicy policy, Clock clock) {
        Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limits = new Limits(policy);
        this.maxEntries = policy.maxEntries();
    }

    @Override
    public RateLimitDecision tryAcquire(ClientAddress client) {
        long now = nowNanos();
        ClientKey key = client.rateLimitKey();
        RateLimitDecision decision = decide(key, now);
        if (decision instanceof CapacityExhausted && sweepIfDue(now)) {
            decision = decide(key, now);
        }
        return decision;
    }

    @Override
    public void recordFailure(ClientAddress client) {
        long now = nowNanos();
        ClientKey key = client.rateLimitKey();
        if (!apply(key, entry -> entry.recordFailure(now, limits)) && sweepIfDue(now)) {
            apply(key, entry -> entry.recordFailure(now, limits));
        }
    }

    private RateLimitDecision decide(ClientKey key, long now) {
        RateLimitDecision[] decision = {EXHAUSTED};
        apply(key, entry -> decision[0] = entry.tryAcquire(now, limits));
        return decision[0];
    }

    /**
     * Runs {@code operation} on the entry of {@code key}; false if a new entry has no slot. Nothing
     * between the reservation of a slot and the insertion can throw except an {@link Error} that
     * ends the process, so a reserved slot is never left without its entry.
     */
    private boolean apply(ClientKey key, Consumer<Entry> operation) {
        boolean[] placed = {true};
        table.compute(key, (k, existing) -> {
            Entry entry = existing;
            if (entry == null) {
                if (!reserveSlot()) {
                    placed[0] = false;
                    return null;
                }
                entry = new Entry(limits);
            }
            operation.accept(entry);
            return entry;
        });
        return placed[0];
    }

    private boolean reserveSlot() {
        int current;
        do {
            current = reserved.get();
            if (current >= maxEntries) {
                return false;
            }
        } while (!reserved.compareAndSet(current, current + 1));
        return true;
    }

    /** One thread at most sweeps in any second; true if this call was the one that did. */
    private boolean sweepIfDue(long now) {
        long due = nextSweepAt.get();
        if (now < due || !nextSweepAt.compareAndSet(due, now + SWEEP_INTERVAL_NANOS)) {
            return false;
        }
        for (ClientKey key : table.keySet()) {
            reclaim(key, now);
        }
        return true;
    }

    /**
     * Removes the entry of {@code key} if forgetting it changes no answer. The slot goes back to
     * the counter only after the map has removed the entry: released inside the removal, another
     * thread could insert into the free slot while the old entry was still in the map, and the
     * table would hold more than {@code maxEntries} for an instant.
     */
    private void reclaim(ClientKey key, long now) {
        boolean[] removed = {false};
        table.computeIfPresent(key, (k, entry) -> {
            removed[0] = entry.isReclaimable(now, limits);
            return removed[0] ? null : entry;
        });
        if (removed[0]) {
            reserved.decrementAndGet();
        }
    }

    private long nowNanos() {
        Instant instant = clock.instant();
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), NANOS_PER_SECOND),
                instant.getNano());
    }

    /** The policy in nanoseconds, so that an entry never converts a duration. */
    private record Limits(int requestLimit, long requestWindow, int failureThreshold,
            long failureWindow, long restrictedInterval, long restrictionCap) {

        Limits(RateLimitPolicy policy) {
            this(policy.requestLimit(), policy.requestWindow().toNanos(), policy.failureThreshold(),
                    policy.failureWindow().toNanos(), policy.restrictedInterval().toNanos(),
                    policy.restrictionCap().toNanos());
        }
    }

    /**
     * What is known of one client. Never touched outside {@link ConcurrentHashMap#compute} of its
     * key. Ages are always {@code now - instant}, never {@code instant + duration}, which could
     * overflow for a long duration.
     */
    private static final class Entry {

        private final LongRing admitted;
        private final LongRing failures;
        private boolean hasAdmitted;
        private long lastAdmittedAt;
        private boolean restricted;
        private long restrictedSince;

        Entry(Limits limits) {
            this.admitted = new LongRing(limits.requestLimit());
            this.failures = new LongRing(limits.failureThreshold());
        }

        RateLimitDecision tryAcquire(long now, Limits limits) {
            settle(now, limits);
            long wait = 0;
            if (admitted.size() >= limits.requestLimit()) {
                wait = limits.requestWindow() - (now - admitted.first());
            }
            if (restricted && hasAdmitted) {
                long sinceLast = now - lastAdmittedAt;
                if (sinceLast < limits.restrictedInterval()) {
                    wait = Math.max(wait, limits.restrictedInterval() - sinceLast);
                }
            }
            if (wait > 0) {
                return new Limited(Duration.ofNanos(wait));
            }
            admitted.add(now);
            hasAdmitted = true;
            lastAdmittedAt = now;
            return ADMITTED;
        }

        void recordFailure(long now, Limits limits) {
            settle(now, limits);
            failures.add(now);
            if (!restricted
                    && failures.countYoungerThan(now, limits.failureWindow())
                            >= limits.failureThreshold()) {
                restricted = true;
                restrictedSince = now;
            }
        }

        /** True when forgetting the entry would change no answer, now or later. */
        boolean isReclaimable(long now, Limits limits) {
            settle(now, limits);
            return admitted.size() == 0 && !restricted && failures.isQuiet(now, limits.failureWindow())
                    && !(hasAdmitted && now - lastAdmittedAt < limits.restrictedInterval());
        }

        /** Brings the entry to {@code now}: expired admissions go and a finished restriction ends. */
        private void settle(long now, Limits limits) {
            while (admitted.size() > 0 && now - admitted.first() >= limits.requestWindow()) {
                admitted.removeFirst();
            }
            if (!restricted) {
                return;
            }
            if (now - restrictedSince >= limits.restrictionCap()) {
                restricted = false;
                failures.clear();
            } else if (failures.isQuiet(now, limits.failureWindow())) {
                restricted = false;
            }
        }
    }

    /**
     * A queue of instants that never holds more than {@code capacity} of them: adding to a full one
     * drops the oldest. It starts small and grows, so a policy with a large limit costs nothing
     * for a client that makes few requests.
     */
    private static final class LongRing {

        private static final int INITIAL_LENGTH = 16;

        private final int capacity;
        private long[] values;
        private int head;
        private int size;

        LongRing(int capacity) {
            this.capacity = capacity;
            this.values = new long[Math.min(capacity, INITIAL_LENGTH)];
        }

        int size() {
            return size;
        }

        long first() {
            return values[head];
        }

        void removeFirst() {
            head = (head + 1) % values.length;
            size--;
        }

        void clear() {
            head = 0;
            size = 0;
        }

        void add(long value) {
            if (size == capacity) {
                removeFirst();
            }
            if (size == values.length) {
                grow();
            }
            values[(head + size) % values.length] = value;
            size++;
        }

        /** How many instants are younger than {@code window}: strictly, so the edge is outside. */
        int countYoungerThan(long now, long window) {
            int count = 0;
            for (int i = 0; i < size; i++) {
                if (now - values[(head + i) % values.length] < window) {
                    count++;
                }
            }
            return count;
        }

        /** True when no instant is younger than {@code window}, the newest included. */
        boolean isQuiet(long now, long window) {
            return size == 0 || now - values[(head + size - 1) % values.length] >= window;
        }

        private void grow() {
            long[] bigger = new long[(int) Math.min(capacity, 2L * values.length)];
            for (int i = 0; i < size; i++) {
                bigger[i] = values[(head + i) % values.length];
            }
            values = bigger;
            head = 0;
        }
    }
}
