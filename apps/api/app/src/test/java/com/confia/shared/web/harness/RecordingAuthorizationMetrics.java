package com.confia.shared.web.harness;

import com.confia.kernel.Permission;
import com.confia.shared.web.authorization.AuthorizationMetrics;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** The double of the authorization metrics port: it counts the signals it receives. */
public final class RecordingAuthorizationMetrics implements AuthorizationMetrics {

    private final AtomicInteger resolutionFailed = new AtomicInteger();
    private final AtomicInteger denialAuditFailed = new AtomicInteger();
    private final AtomicInteger denialTrackerFull = new AtomicInteger();
    private final List<Permission> denialsOmitted = new CopyOnWriteArrayList<>();

    @Override
    public void denialOmitted(Permission permission) {
        denialsOmitted.add(permission);
    }

    @Override
    public void denialAuditFailed() {
        denialAuditFailed.incrementAndGet();
    }

    @Override
    public void denialTrackerFull() {
        denialTrackerFull.incrementAndGet();
    }

    @Override
    public void resolutionFailed() {
        resolutionFailed.incrementAndGet();
    }

    /** How many failed resolutions were signalled. */
    public int resolutionFailedCount() {
        return resolutionFailed.get();
    }

    /** How many audit writes of a denial were signalled as failed. */
    public int denialAuditFailedCount() {
        return denialAuditFailed.get();
    }

    /** How many times the denial tracker was signalled as full. */
    public int denialTrackerFullCount() {
        return denialTrackerFull.get();
    }

    /** The permission of each denial signalled as omitted, in order. */
    public List<Permission> denialsOmitted() {
        return List.copyOf(denialsOmitted);
    }
}
