package com.confia.shared.observability.metrics;

import com.confia.shared.web.ratelimit.CapacityReason;
import com.confia.shared.web.ratelimit.RateLimitMetrics;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The interim adapter of {@link RateLimitMetrics}: there is no metrics registry in the backend yet,
 * so the signal is one fixed, structured {@code WARN} event (web-edge-foundations design.md,
 * decision 17, dated notes of 2026-10-05 and 2026-10-06; review finding I-2). The alert is mounted
 * on its {@code event} field. The adapter toward Prometheus arrives with the observability change
 * and replaces this one without touching the limiter.
 *
 * <p>The event is {@code rate limit capacity exhausted} with four key-value fields: {@code event}
 * ({@value #EVENT}), {@code policy}, {@code reason} (the {@link CapacityReason} in lower snake
 * case, such as {@code table_full}, which tells an attack from a defect of the wiring) and {@code
 * suppressed}, the number of signals held back since the previous event of that policy and reason.
 * Nothing of the client or the request is known here, so nothing of it can be written: no address,
 * no path, no header, no key, and never an exception, whose message may carry any of them.
 *
 * <p>It is bounded, because the condition it reports is one an attacker can sustain: at most one
 * event per second <b>per policy and reason</b>, so a flood of refusals costs one short line a
 * second whatever its size. The bound is per reason as well as per policy so that a flood of one
 * reason, normally a full table, cannot hide in the same second the rare defect (a missing origin,
 * a failing limiter) that an operator must act on. The state is closed: the policies are the few
 * names the code declares (see {@link RateLimitMetrics#capacityExhausted}) times five reasons. Time
 * comes from a monotonic nanosecond source, {@link System#nanoTime()} in production, so a step of
 * the system clock can neither silence the alert nor flood it.
 *
 * <p>An event is written only when a signal arrives after the interval, never on its own: the
 * signals held back after the last event written are reported only with the next one (the count
 * of a flood that ends is not lost, it waits for the next signal).
 */
public final class LogRateLimitMetrics implements RateLimitMetrics {

    /** The value of the {@code event} field, the name the alert is mounted on. */
    static final String EVENT = "rate_limit_capacity_exhausted";

    private static final Logger LOG = LoggerFactory.getLogger(LogRateLimitMetrics.class);
    private static final long INTERVAL_NANOS = 1_000_000_000L;

    private final LongSupplier monotonicNanos;
    private final ConcurrentHashMap<Key, Window> windows = new ConcurrentHashMap<>();

    /** The adapter of production: time is {@link System#nanoTime()}, and nothing else. */
    public LogRateLimitMetrics() {
        this(System::nanoTime);
    }

    /** For tests, which move the source by hand. */
    LogRateLimitMetrics(LongSupplier monotonicNanos) {
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    @Override
    public void capacityExhausted(String policy, CapacityReason reason) {
        long now = monotonicNanos.getAsLong();
        long[] emit = {-1};
        windows.compute(new Key(policy, reason), (key, window) -> {
            if (window == null) {
                emit[0] = 0;
                return new Window(now, 0);
            }
            // Only the difference of two readings is meaningful: the counter may wrap or be negative.
            if (now - window.openedAt >= INTERVAL_NANOS) {
                emit[0] = window.suppressed;
                return new Window(now, 0);
            }
            return new Window(window.openedAt, window.suppressed + 1);
        });
        if (emit[0] >= 0) {
            LOG.atWarn().addKeyValue("event", EVENT).addKeyValue("policy", policy)
                    .addKeyValue("reason", reason.name().toLowerCase(Locale.ROOT))
                    .addKeyValue("suppressed", emit[0]).log("rate limit capacity exhausted");
        }
    }

    /** What an event is bounded by: a policy and the reason of the signal. */
    private record Key(String policy, CapacityReason reason) {
    }

    /** When the last event of a key was written and how many signals came after it. */
    private record Window(long openedAt, long suppressed) {
    }
}
