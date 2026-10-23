package com.confia.identity.application;

import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.shared.security.ClientAddress;
import java.time.Instant;

/**
 * The port behind the "five distinct accounts in ten minutes" signal
 * (session-endpoints-cookie-and-csrf design.md decision 12). It is a signal and not a control: the
 * control of a client address is the rate limiter at the edge, and an implementation that cannot
 * record a failure says so by not signalling.
 */
public interface LoginFailureSpread {

    /**
     * Records that {@code identifier} failed from {@code client} at {@code now}.
     *
     * @return {@code true} only when this call brings the number of distinct identifiers that failed
     *     from that address in the last ten minutes to the threshold of five
     */
    boolean recordFailure(ClientAddress client, IdentifierFingerprint identifier, Instant now);
}
