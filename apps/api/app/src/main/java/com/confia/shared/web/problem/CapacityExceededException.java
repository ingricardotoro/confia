package com.confia.shared.web.problem;

/**
 * The server has no capacity for this request, and nobody can say when it will: it answers {@code
 * 503 capacity-exceeded} with no {@code Retry-After} and no hint of the cause (web-edge-foundations
 * design.md, decisions 17 and 18). The rate limiter raises it when its table is full of live
 * entries or when it cannot decide, and the delay materializer will when its permits run out. The
 * response never says which. What tells an operator is the signal of the one that raised it: for the
 * rate limiter, the {@code reason} of the event of {@code RateLimitMetrics}, which separates a full
 * table from a defect of the wiring; a framework {@code 503} (an async timeout, a {@code
 * ResponseStatusException}) answers the same code and leaves only the server error log. It carries
 * no data and no stack trace.
 */
public final class CapacityExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CapacityExceededException() {
        super("capacity exceeded", null, false, false);
    }
}
