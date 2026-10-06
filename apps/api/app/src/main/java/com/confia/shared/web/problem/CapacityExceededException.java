package com.confia.shared.web.problem;

/**
 * The server has no capacity for this request, and nobody can say when it will: it answers {@code
 * 503 capacity-exceeded} with no {@code Retry-After} and no hint of the cause (web-edge-foundations
 * design.md, decisions 17 and 18). The rate limiter raises it when its table is full of live
 * entries or when it cannot decide, and the delay materializer will when its permits run out. The
 * server's log, not the response, tells which. It carries no data and no stack trace.
 */
public final class CapacityExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CapacityExceededException() {
        super("capacity exceeded", null, false, false);
    }
}
