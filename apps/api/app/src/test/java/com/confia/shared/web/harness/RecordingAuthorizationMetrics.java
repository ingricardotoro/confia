package com.confia.shared.web.harness;

import com.confia.shared.web.authorization.AuthorizationMetrics;
import java.util.concurrent.atomic.AtomicInteger;

/** The double of the authorization metrics port: it counts the signals it receives. */
public final class RecordingAuthorizationMetrics implements AuthorizationMetrics {

    private final AtomicInteger resolutionFailed = new AtomicInteger();

    @Override
    public void resolutionFailed() {
        resolutionFailed.incrementAndGet();
    }

    /** How many failed resolutions were signalled. */
    public int resolutionFailedCount() {
        return resolutionFailed.get();
    }
}
