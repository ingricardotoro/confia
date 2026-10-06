package com.confia.shared.security;

import java.time.Duration;

/**
 * The values of one rate-limit policy (web-edge-foundations design.md, decisions 15 and 16). Every
 * value is positive: a zero or negative one is a configuration mistake, and the message names the
 * value so that the person who set it can find it.
 *
 * @param requestLimit layer 1: the most requests admitted within {@code requestWindow}
 * @param requestWindow layer 1: the sliding window of the request count
 * @param failureThreshold layer 2: the failures within {@code failureWindow} that restrict a client
 * @param failureWindow layer 2: how long a failure counts, and how long a restricted client may
 *     stay quiet before the restriction ends
 * @param restrictedInterval layer 2: the least time between two admitted attempts while restricted
 * @param restrictionCap layer 2: the longest a restriction lasts, after which the failures start
 *     again from zero
 * @param maxEntries the most clients the table holds, at most one million
 */
public record RateLimitPolicy(int requestLimit, Duration requestWindow, int failureThreshold,
        Duration failureWindow, Duration restrictedInterval, Duration restrictionCap,
        int maxEntries) {

    /**
     * The most a {@code requestLimit} or a {@code failureThreshold} may be (review S-2). Every
     * operation on a client runs under the lock of its table bin and the failure count walks the
     * whole ring, so the bound keeps that work, and the memory of one entry, small whatever the
     * configuration says. Three orders of magnitude above the ten of the administrative login.
     */
    private static final int MAX_COUNT = 10_000;

    /**
     * The most {@code maxEntries} may be (review I-3). The table is bounded so that a flood of clients
     * cannot exhaust the memory of the process, and that holds only if the bound itself is bounded: a
     * configuration mistake of two billion entries would be the same exhaustion, written as a value.
     * Twenty times the fifty thousand of the administrative login.
     */
    private static final int MAX_ENTRIES = 1_000_000;

    public RateLimitPolicy {
        positive(requestLimit, "requestLimit");
        atMost(requestLimit, "requestLimit", MAX_COUNT);
        positive(requestWindow, "requestWindow");
        positive(failureThreshold, "failureThreshold");
        atMost(failureThreshold, "failureThreshold", MAX_COUNT);
        positive(failureWindow, "failureWindow");
        positive(restrictedInterval, "restrictedInterval");
        positive(restrictionCap, "restrictionCap");
        positive(maxEntries, "maxEntries");
        atMost(maxEntries, "maxEntries", MAX_ENTRIES);
    }

    private static void positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void atMost(int value, String name, int bound) {
        if (value > bound) {
            throw new IllegalArgumentException(name + " must not exceed " + bound);
        }
    }

    /** A duration must be positive and small enough for the limiter to count it in nanoseconds. */
    private static void positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            value.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " is too large to be counted in nanoseconds");
        }
    }
}
