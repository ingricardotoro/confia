package com.confia.shared.security;

/**
 * The rate limiter of one policy, keyed by client (web-edge-foundations design.md, decision 15).
 * It counts two things apart, so that exceeding one never alters the other: the requests it
 * admitted, and the failures the caller reported. There is no "success" operation: a successful
 * login never clears the failures of an address.
 *
 * <p>An instance serves one policy, so that filling the table of one policy never shuts out
 * another. Implementations fail closed: anything they cannot decide is a refusal, never an
 * admission.
 */
public interface RateLimiter {

    /**
     * Decides whether {@code client} may make a request now, and counts the request only if it is
     * admitted: a rejection is never recorded and never moves a window.
     */
    RateLimitDecision tryAcquire(ClientAddress client);

    /** Reports a failure of {@code client}, the second dimension of the policy. */
    void recordFailure(ClientAddress client);
}
