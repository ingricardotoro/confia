package com.confia.shared.security;

import com.confia.shared.security.RateLimitDecision.Admitted;
import com.confia.shared.security.RateLimitDecision.CapacityExhausted;
import com.confia.shared.security.RateLimitDecision.Limited;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.ObjLongConsumer;

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
 * the table never holds more than {@code maxEntries} entries at any instant.
 *
 * <p><b>Time</b> comes from a monotonic nanosecond source, {@link System#nanoTime()} in production,
 * and only differences between two readings are ever used, so the wrap of the counter is harmless
 * and its origin means nothing. The wall clock is deliberately not involved: a step of the system
 * time (an operator, NTP, a virtual machine resumed) would, forwards, make every window and every
 * restriction look expired and unlock every client, and, backwards, freeze the sweep until the
 * table fills and every new client is refused. The time of an operation is read inside the lock of
 * the client it concerns, so the instants an entry records are in the order in which the
 * operations were applied to it.
 */
public final class InMemoryRateLimiter implements RateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long SWEEP_INTERVAL_NANOS = NANOS_PER_SECOND;
    private static final RateLimitDecision ADMITTED = new Admitted();
    private static final RateLimitDecision EXHAUSTED = new CapacityExhausted();

    private final LongSupplier monotonicNanos;
    private final Limits limits;
    private final int maxEntries;
    private final ConcurrentHashMap<ClientKey, Entry> table = new ConcurrentHashMap<>();
    private final AtomicInteger reserved = new AtomicInteger();
    private final AtomicLong nextSweepAt;

    /** The limiter of production: time is {@link System#nanoTime()}, and nothing else. */
    public InMemoryRateLimiter(RateLimitPolicy policy) {
        this(policy, System::nanoTime);
    }

    /**
     * For tests only, hence not public: the one source allowed is a monotonic one, and the only way
     * to choose it from outside the package is the constructor above.
     */
    InMemoryRateLimiter(RateLimitPolicy policy, LongSupplier monotonicNanos) {
        Objects.requireNonNull(policy, "policy");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        this.limits = new Limits(policy);
        this.maxEntries = policy.maxEntries();
        this.nextSweepAt = new AtomicLong(monotonicNanos.getAsLong());
    }

    @Override
    public RateLimitDecision tryAcquire(ClientAddress client) {
        ClientKey key = client.rateLimitKey();
        RateLimitDecision decision = decide(key);
        if (decision instanceof CapacityExhausted && sweepIfDue(now())) {
            decision = decide(key);
        }
        return decision;
    }

    @Override
    public void recordFailure(ClientAddress client) {
        ClientKey key = client.rateLimitKey();
        if (!apply(key, (entry, now) -> entry.recordFailure(now, limits)) && sweepIfDue(now())) {
            apply(key, (entry, now) -> entry.recordFailure(now, limits));
        }
    }

    /** The number of clients the table holds now; the tests read it at rest. */
    int size() {
        return table.size();
    }

    private RateLimitDecision decide(ClientKey key) {
        RateLimitDecision[] decision = {EXHAUSTED};
        apply(key, (entry, now) -> decision[0] = entry.tryAcquire(now, limits));
        return decision[0];
    }

    /**
     * Runs {@code operation} on the entry of {@code key} with the time read inside its lock; false
     * if a new entry has no slot. Nothing between the reservation of a slot and the insertion can
     * throw except an {@link Error} that ends the process, so a reserved slot is never left without
     * its entry.
     */
    private boolean apply(ClientKey key, ObjLongConsumer<Entry> operation) {
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
            operation.accept(entry, now());
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

    /**
     * One thread at most sweeps in any second; true if this call was the one that did. The due
     * instant is compared as a difference, never with {@code <}, so that it survives the wrap.
     */
    private boolean sweepIfDue(long now) {
        long due = nextSweepAt.get();
        if (now - due < 0 || !nextSweepAt.compareAndSet(due, now + SWEEP_INTERVAL_NANOS)) {
            return false;
        }
        for (ClientKey key : table.keySet()) {
            reclaim(key);
        }
        return true;
    }

    /**
     * Removes the entry of {@code key} if forgetting it changes no answer. The slot goes back to
     * the counter only after the map has removed the entry: released inside the removal, another
     * thread could insert into the free slot while the old entry was still in the map, and the
     * table would hold more than {@code maxEntries} for an instant.
     */
    private void reclaim(ClientKey key) {
        boolean[] removed = {false};
        table.computeIfPresent(key, (k, entry) -> {
            removed[0] = entry.isReclaimable(now(), limits);
            return removed[0] ? null : entry;
        });
        if (removed[0]) {
            reserved.decrementAndGet();
        }
    }

    private long now() {
        return monotonicNanos.getAsLong();
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
