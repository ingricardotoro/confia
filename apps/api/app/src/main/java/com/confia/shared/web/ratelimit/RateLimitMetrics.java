package com.confia.shared.web.ratelimit;

/**
 * What the rate limiter at the edge reports to observability (web-edge-foundations design.md,
 * decision 17, dated note of 2026-10-05; docs/07-observabilidad-y-operaciones.md section 1.1: code
 * emits metrics through a port, and the adapter lives in {@code shared/observability/metrics}).
 *
 * <p>It is deliberately as small as the one signal it exists for. The adapter that serves it
 * today writes a log event; the one toward Prometheus replaces it with the observability change,
 * without touching the limiter or the interceptor. Nothing about the client or the request
 * reaches the port: the policy is the only argument, so no adapter can leak more.
 */
public interface RateLimitMetrics {

    /**
     * The limiter of {@code policy} could not take another client, or the edge could not ask it:
     * its table is full of live entries, the request had no usable origin, or the limiter failed
     * unexpectedly. The request was refused with {@code 503}. The signal behind the alert of the
     * self-denial risk (review finding I-2).
     *
     * @param policy the name of the policy, which the code chose and the start validated
     */
    void capacityExhausted(String policy);
}
