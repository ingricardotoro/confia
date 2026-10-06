package com.confia.shared.web.ratelimit;

/**
 * A request the limiter refused because its client is over its limit: it answers {@code 429
 * too-many-requests} with {@code Retry-After} (web-edge-foundations design.md, decision 17). It
 * carries the wait and nothing else, and no stack trace, because a flood of refused requests is
 * its normal use and each one would otherwise capture a trace nobody reads.
 */
public final class TooManyRequestsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    /** @param retryAfterSeconds the value of {@code Retry-After}: whole seconds, at least one */
    public TooManyRequestsException(long retryAfterSeconds) {
        super("rate limit exceeded", null, false, false);
        if (retryAfterSeconds < 1) {
            throw new IllegalArgumentException("a limited request waits at least one second");
        }
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** The value of the {@code Retry-After} header. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
