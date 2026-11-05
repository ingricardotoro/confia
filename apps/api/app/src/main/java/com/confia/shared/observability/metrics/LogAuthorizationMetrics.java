package com.confia.shared.observability.metrics;

import com.confia.shared.web.authorization.AuthorizationMetrics;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The interim adapter of {@link AuthorizationMetrics}: there is no metrics registry in the backend
 * yet, so the signal is one fixed, structured {@code WARN} event, as for the rate limiter
 * (rbac-permission-matrix-and-audit-integration design.md D11). The alert is mounted on its {@code
 * event} field. The event carries nothing of the request, the actor or the failure, and it is written
 * at most once per second so a database that is down costs one short line a second.
 */
public final class LogAuthorizationMetrics implements AuthorizationMetrics {

    /** The value of the {@code event} field, the name the alert is mounted on. */
    static final String RESOLUTION_FAILED = "authorization_resolution_failed";

    private static final Logger LOG = LoggerFactory.getLogger(LogAuthorizationMetrics.class);
    private static final long INTERVAL_NANOS = 1_000_000_000L;

    private final LongSupplier monotonicNanos;
    private final AtomicLong lastWrittenAt = new AtomicLong();
    private final AtomicLong suppressed = new AtomicLong();
    private volatile boolean written;

    public LogAuthorizationMetrics() {
        this(System::nanoTime);
    }

    LogAuthorizationMetrics(LongSupplier monotonicNanos) {
        this.monotonicNanos = monotonicNanos;
    }

    @Override
    public synchronized void resolutionFailed() {
        long now = monotonicNanos.getAsLong();
        if (written && now - lastWrittenAt.get() < INTERVAL_NANOS) {
            suppressed.incrementAndGet();
            return;
        }
        long held = suppressed.getAndSet(0);
        lastWrittenAt.set(now);
        written = true;
        LOG.atWarn().addKeyValue("event", RESOLUTION_FAILED).addKeyValue("suppressed", held)
                .log("authorization resolution failed");
    }
}
