package com.confia.shared.web.ratelimit;

/**
 * What the rate limiter at the edge reports to observability (web-edge-foundations design.md,
 * decision 17, dated notes of 2026-10-05 and 2026-10-06; docs/07-observabilidad-y-operaciones.md
 * section 1.1: code emits metrics through a port, and the adapter lives in {@code
 * shared/observability/metrics}).
 *
 * <p>It is deliberately as small as the one signal it exists for. The adapter that serves it
 * today writes a log event; the one toward Prometheus replaces it with the observability change,
 * without touching the limiter or the interceptor. Nothing about the client or the request
 * reaches the port: the policy and a closed {@link CapacityReason} are the only arguments, so no
 * adapter can leak more.
 */
public interface RateLimitMetrics {

    /**
     * The limiter of {@code policy} could not take the request, or the edge could not ask it. The
     * request was refused with {@code 503}, which says nothing of why; {@code reason} does, for the
     * operator. The signal behind the alert of the self-denial risk (review finding I-2).
     *
     * <p>{@code policy} must be a compile-time constant, the name written in a {@code
     * @RateLimited} annotation or registered at the start, never a value taken from the request:
     * an adapter keeps state per policy and per reason, and that state is bounded only by the set
     * of policies the code declares.
     *
     * @param policy the name of the policy, which the code chose and the start validated
     * @param reason the cause, one of a closed set
     */
    void capacityExhausted(String policy, CapacityReason reason);
}
