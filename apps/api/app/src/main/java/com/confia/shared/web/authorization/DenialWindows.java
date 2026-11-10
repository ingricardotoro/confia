package com.confia.shared.web.authorization;

import com.confia.kernel.Permission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The cap, the window and the pending summaries of the permission denials, in memory
 * (rbac-permission-matrix-and-audit-integration design.md D10). Per key (institution, account,
 * permission) the first {@code maxFullRows} denials of a window are written as full audit rows and the
 * rest are only counted; when the window closes the count becomes one summary, which a later denial
 * writes. It holds no clock and no database: the caller passes the instant of each denial.
 *
 * <p>The contract of {@link ConcurrentHashMap#compute} forbids a mapping function to touch another
 * mapping of the same map, so three rules hold. A mapping function decides only on the window of its own
 * key and touches only the slot counter and the pending queue, which are other structures. The sweep
 * runs outside every mapping function: it walks a weakly consistent view and removes each closed window
 * with {@code computeIfPresent} on that same key, so a denial of that key is either counted in the old
 * window before it goes or opens a new one after, and none is lost or counted twice. The gate of the
 * sweep is one {@link AtomicLong} won by compare-and-set.
 *
 * <p>The sum is kept per key: the full rows written plus the summaries written, pending or in a live
 * window equal the denials recorded. A full row that fails to be written is given back by {@link
 * #fullRowFailed} or {@link #untrackedRowFailed}, and a clock that steps back closes the window like
 * one that ran out (it never reports fewer rows than denials).
 */
public final class DenialWindows {

    /** What the cap decided for one denial. */
    public record Decision(Kind kind, Instant windowStart) {

        /** FULL_ROW: write a full row. OMITTED: count it only. UNTRACKED: no state, write a full row. */
        public enum Kind { FULL_ROW, OMITTED, UNTRACKED }

        static final Decision OMITTED = new Decision(Kind.OMITTED, null);
        static final Decision UNTRACKED = new Decision(Kind.UNTRACKED, null);

        static Decision fullRow(Instant windowStart) {
            return new Decision(Kind.FULL_ROW, windowStart);
        }
    }

    /** The key of a window: the institution is part of it because the account id is only unique within one. */
    public record Key(UUID institutionId, UUID accountId, Permission permission) {

        public Key {
            Objects.requireNonNull(institutionId, "institutionId");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(permission, "permission");
        }
    }

    /** The count of a closed window, waiting for a denial to write it. */
    public record PendingSummary(Key key, Instant windowStart, int omittedCount) {
    }

    private record Window(Instant windowStart, int full, int omitted) {
    }

    private static final long SWEEP_INTERVAL_MILLIS = 1_000;

    private final int maxFullRows;
    private final Duration window;
    private final int maxTrackedKeys;
    private final int sweepLimit;
    private final AuthorizationMetrics metrics;
    private final ConcurrentHashMap<Key, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger slots = new AtomicInteger();
    private final LinkedBlockingDeque<PendingSummary> pending;
    private final AtomicLong nextSweepAt = new AtomicLong();

    /**
     * @param maxFullRows full audit rows per key and window
     * @param window how long a window lasts
     * @param maxTrackedKeys the bound of keys held
     * @param pendingCapacity the bound of summaries waiting
     * @param sweepLimit the most windows one sweep removes
     * @param metrics told when a summary is discarded because the queue is full
     */
    public DenialWindows(int maxFullRows, Duration window, int maxTrackedKeys, int pendingCapacity,
            int sweepLimit, AuthorizationMetrics metrics) {
        if (maxFullRows < 1 || maxTrackedKeys < 1 || pendingCapacity < 1 || sweepLimit < 1) {
            throw new IllegalArgumentException("every bound of the denial windows is at least 1");
        }
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("the window of the denials is positive");
        }
        this.maxFullRows = maxFullRows;
        this.window = window;
        this.maxTrackedKeys = maxTrackedKeys;
        this.sweepLimit = sweepLimit;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.pending = new LinkedBlockingDeque<>(pendingCapacity);
    }

    /** The bounds of production: 10 000 summaries waiting and 500 windows per sweep. */
    public static DenialWindows of(AuthorizationProperties properties, AuthorizationMetrics metrics) {
        return new DenialWindows(properties.maxFullRows(), properties.window(),
                properties.maxTrackedKeys(), 10_000, 500, metrics);
    }

    /** Counts one denial and says whether it is a full row, only counted, or not tracked. */
    public Decision record(Key key, Instant now) {
        Decision decision = attempt(key, now);
        if (decision != null) {
            return decision;
        }
        // No place for a new key: free the windows that closed, outside any mapping function, and
        // try once more.
        sweepIfDue(now);
        decision = attempt(key, now);
        return decision != null ? decision : Decision.UNTRACKED;
    }

    private Decision attempt(Key key, Instant now) {
        Decision[] decided = new Decision[1];
        windows.compute(key, (k, current) -> {
            if (current == null) {
                if (!reserve()) {
                    return null;
                }
                decided[0] = Decision.fullRow(now);
                return new Window(now, 1, 0);
            }
            if (isClosed(current, now)) {
                if (current.omitted() > 0) {
                    enqueue(new PendingSummary(key, current.windowStart(), current.omitted()));
                }
                decided[0] = Decision.fullRow(now);
                return new Window(now, 1, 0);
            }
            if (current.full() < maxFullRows) {
                decided[0] = Decision.fullRow(current.windowStart());
                return new Window(current.windowStart(), current.full() + 1, current.omitted());
            }
            decided[0] = Decision.OMITTED;
            return new Window(current.windowStart(), current.full(), current.omitted() + 1);
        });
        return decided[0];
    }

    /** The write of a full row of window {@code windowStart} failed: its denial is counted instead. */
    public void fullRowFailed(Key key, Instant windowStart) {
        windows.compute(key, (k, current) -> {
            if (current != null && current.windowStart().equals(windowStart)) {
                return new Window(windowStart, current.full() - 1, current.omitted() + 1);
            }
            enqueue(new PendingSummary(key, windowStart, 1));
            return current;
        });
    }

    /** The write of the row of a denial that was not tracked failed: its denial is counted as a summary of one. */
    public void untrackedRowFailed(Key key, Instant now) {
        enqueue(new PendingSummary(key, now, 1));
    }

    /**
     * Sweeps if the gate is open: at most once a second in the whole process, in memory only. A next
     * instant more than one second ahead can only come from a clock that stepped back, and it counts as
     * due, so a step back of an hour does not stop the sweep for an hour.
     */
    public void sweepIfDue(Instant now) {
        long nowMillis = now.toEpochMilli();
        long next = nextSweepAt.get();
        boolean due = nowMillis >= next || next - nowMillis > SWEEP_INTERVAL_MILLIS;
        if (due && nextSweepAt.compareAndSet(next, nowMillis + SWEEP_INTERVAL_MILLIS)) {
            sweep(now);
        }
    }

    /** Sweeps with no gate and returns how many windows it removed (at most the limit). */
    int sweepNow(Instant now) {
        return sweep(now);
    }

    private int sweep(Instant now) {
        int moved = 0;
        for (Map.Entry<Key, Window> entry : windows.entrySet()) {
            if (moved >= sweepLimit) {
                break;
            }
            if (!isClosed(entry.getValue(), now)) {
                continue;
            }
            boolean[] removed = new boolean[1];
            windows.computeIfPresent(entry.getKey(), (k, current) -> {
                if (!isClosed(current, now)) {
                    return current;
                }
                if (current.omitted() > 0) {
                    enqueue(new PendingSummary(k, current.windowStart(), current.omitted()));
                }
                removed[0] = true;
                return null;
            });
            if (removed[0]) {
                slots.decrementAndGet();
                moved++;
            }
        }
        return moved;
    }

    /** The next summary to write, or empty. */
    public Optional<PendingSummary> pollPending() {
        return Optional.ofNullable(pending.pollFirst());
    }

    /** Puts a summary whose write failed back at the head; false when the queue is full. */
    public boolean requeue(PendingSummary summary) {
        return pending.offerFirst(summary);
    }

    /**
     * Closes every window that has omissions, closed or not, and takes the whole queue, for the
     * shutdown: the summaries that must still be written.
     */
    public List<PendingSummary> closeAllForShutdown(Instant now) {
        for (Key key : List.copyOf(windows.keySet())) {
            boolean[] removed = new boolean[1];
            windows.computeIfPresent(key, (k, current) -> {
                if (current.omitted() == 0) {
                    return current;
                }
                enqueue(new PendingSummary(k, current.windowStart(), current.omitted()));
                removed[0] = true;
                return null;
            });
            if (removed[0]) {
                slots.decrementAndGet();
            }
        }
        List<PendingSummary> drained = new ArrayList<>();
        pending.drainTo(drained);
        return drained;
    }

    /** How many keys are held. */
    public int size() {
        return windows.size();
    }

    /** How many summaries wait. */
    public int pendingSize() {
        return pending.size();
    }

    private boolean isClosed(Window current, Instant now) {
        return !now.isBefore(current.windowStart().plus(window)) || now.isBefore(current.windowStart());
    }

    private boolean reserve() {
        while (true) {
            int held = slots.get();
            if (held >= maxTrackedKeys) {
                return false;
            }
            if (slots.compareAndSet(held, held + 1)) {
                return true;
            }
        }
    }

    private void enqueue(PendingSummary summary) {
        if (!pending.offerLast(summary)) {
            metrics.denialAuditFailed();
        }
    }
}
