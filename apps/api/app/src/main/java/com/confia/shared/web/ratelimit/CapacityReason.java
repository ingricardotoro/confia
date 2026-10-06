package com.confia.shared.web.ratelimit;

/**
 * Why the edge could not take a request for the rate limiter (web-edge-foundations design.md,
 * decision 17, dated note of 2026-10-06; review finding I-2). The response is the same {@code 503}
 * whatever the reason, so that the client learns nothing; the reason is for the operator, who must
 * be able to tell an attack from a defect of the wiring. It is a closed set chosen by the code,
 * never built from the request, an address or an exception message.
 */
public enum CapacityReason {

    /** The table of the policy is full of live entries: the attack the alert exists for. */
    TABLE_FULL,

    /** No {@code RequestOrigin} was bound to the request: a defect of the filter chain. */
    NO_ORIGIN,

    /** The origin has no client address that is an IP literal: nothing to key the limit on. */
    NO_ADDRESS,

    /** The annotation names a policy the registry does not hold: a defect the start should catch. */
    UNKNOWN_POLICY,

    /** The limiter threw something unexpected: a defect of the limiter. */
    LIMITER_FAILURE
}
