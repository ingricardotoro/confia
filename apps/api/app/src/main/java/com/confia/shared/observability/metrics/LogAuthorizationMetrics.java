package com.confia.shared.observability.metrics;

import com.confia.kernel.Permission;
import com.confia.shared.web.authorization.AuthorizationMetrics;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The interim adapter of {@link AuthorizationMetrics}: there is no metrics registry in the backend
 * yet, so each signal is one fixed, structured {@code WARN} event, as for the rate limiter
 * (rbac-permission-matrix-and-audit-integration design.md D11). The alerts are mounted on the {@code
 * event} field. An event carries {@code event}, {@code permission} when the signal has one, and {@code
 * suppressed}, the signals held back since the previous event of its key. It carries nothing of the
 * request, the actor or the failure: no email, address, route or exception.
 *
 * <p>It is written at most once per second per key, the key being the event and, for a denial
 * omitted, its permission. The state is bounded by construction: {@link Permission} is a closed
 * catalog, so the keys are at most the permissions plus three. A signal held back is reported with the
 * next event of its key, never on its own.
 */
public final class LogAuthorizationMetrics implements AuthorizationMetrics {

    /** The values of the {@code event} field, the names the alerts are mounted on. */
    static final String DENIAL_OMITTED = "authorization_denial_omitted";
    static final String DENIAL_AUDIT_FAILED = "authorization_denial_audit_failed";
    static final String RESOLUTION_FAILED = "authorization_resolution_failed";
    static final String DENIAL_TRACKER_FULL = "authorization_denial_tracker_full";

    private static final Logger LOG = LoggerFactory.getLogger(LogAuthorizationMetrics.class);
    private static final long INTERVAL_NANOS = 1_000_000_000L;

    private final LongSupplier monotonicNanos;
    private final ConcurrentHashMap<Key, Window> windows = new ConcurrentHashMap<>();

    /** The adapter of production: time is {@link System#nanoTime()}, and nothing else. */
    public LogAuthorizationMetrics() {
        this(System::nanoTime);
    }

    /** For tests, which move the source by hand. */
    LogAuthorizationMetrics(LongSupplier monotonicNanos) {
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    @Override
    public void denialOmitted(Permission permission) {
        Objects.requireNonNull(permission, "permission");
        signal(new Key(DENIAL_OMITTED, permission), "authorization denial omitted");
    }

    @Override
    public void denialAuditFailed() {
        signal(new Key(DENIAL_AUDIT_FAILED, null), "authorization denial audit failed");
    }

    @Override
    public void resolutionFailed() {
        signal(new Key(RESOLUTION_FAILED, null), "authorization resolution failed");
    }

    @Override
    public void denialTrackerFull() {
        signal(new Key(DENIAL_TRACKER_FULL, null), "authorization denial tracker full");
    }

    /** The number of keys held, for the test of the bound. */
    int trackedKeys() {
        return windows.size();
    }

    private void signal(Key key, String message) {
        long now = monotonicNanos.getAsLong();
        long[] emit = {-1};
        windows.compute(key, (k, window) -> {
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
        if (emit[0] < 0) {
            return;
        }
        var event = LOG.atWarn().addKeyValue("event", key.event);
        if (key.permission != null) {
            event = event.addKeyValue("permission", key.permission.code());
        }
        event.addKeyValue("suppressed", emit[0]).log(message);
    }

    /** What an event is bounded by: its name and, when it has one, its permission. */
    private record Key(String event, Permission permission) {
    }

    /** When the last event of a key was written and how many signals came after it. */
    private record Window(long openedAt, long suppressed) {
    }
}
