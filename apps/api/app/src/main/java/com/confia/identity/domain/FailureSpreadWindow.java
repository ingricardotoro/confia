package com.confia.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The sliding window behind the "five distinct accounts in ten minutes" signal of one client address
 * (session-endpoints-cookie-and-csrf design.md decision 12; scenarios I164, I165, I166 and I224):
 * pure logic, with no clock, no address and no storage of its own. The adapter that keeps one window
 * per address lives in {@code infrastructure}; this class only decides.
 *
 * <p>A failure is <b>live</b> while it is strictly younger than {@link #WINDOW}: the failure of
 * 10:00:00 no longer counts at 10:10:00. The window keeps, for each identifier fingerprint, the
 * instant of its latest failure, so the same identifier failing again does not add to the count
 * (which also makes a repeated call, such as the retry of a transaction, harmless) and keeps that
 * identifier live for longer.
 *
 * <p>{@link #record} answers {@code true} only on the call that <b>brings</b> the number of distinct
 * live identifiers to {@link #THRESHOLD}: not on the sixth, and not when an already counted
 * identifier fails again. If the failures age out until the count falls below the threshold and new
 * ones bring it back to five, the signal is raised again, because the earlier five are no longer a
 * spread inside one window.
 *
 * <p>Not thread-safe: its owner serializes the calls, as a plain value of the adapter's map.
 */
public final class FailureSpreadWindow {

    /** The number of distinct identifiers that makes a spread. */
    public static final int THRESHOLD = 5;

    /** How long a failure counts. */
    public static final Duration WINDOW = Duration.ofMinutes(10);

    private final Map<IdentifierFingerprint, Instant> lastFailureOf = new HashMap<>();

    /**
     * Records a failure of {@code identifier} at {@code now} and tells whether this call raises the
     * signal.
     *
     * @return {@code true} only when this call brings the distinct live identifiers to the threshold
     */
    public boolean record(IdentifierFingerprint identifier, Instant now) {
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(now, "now");
        dropExpired(now);
        boolean alreadyCounted = lastFailureOf.containsKey(identifier);
        lastFailureOf.put(identifier, now);
        return !alreadyCounted && lastFailureOf.size() == THRESHOLD;
    }

    /** Whether any failure is still live at {@code now}; it drops nothing. */
    public boolean hasLiveFailures(Instant now) {
        Objects.requireNonNull(now, "now");
        return lastFailureOf.values().stream().anyMatch(failedAt -> isLive(failedAt, now));
    }

    private void dropExpired(Instant now) {
        lastFailureOf.values().removeIf(failedAt -> !isLive(failedAt, now));
    }

    private static boolean isLive(Instant failedAt, Instant now) {
        return Duration.between(failedAt, now).compareTo(WINDOW) < 0;
    }
}
