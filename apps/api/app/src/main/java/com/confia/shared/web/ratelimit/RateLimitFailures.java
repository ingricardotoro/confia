package com.confia.shared.web.ratelimit;

import com.confia.shared.security.ClientAddress;
import com.confia.shared.security.RateLimiter;
import com.confia.shared.security.RequestOrigin;
import java.util.Objects;
import java.util.Optional;

/**
 * Reports a failure of the client of the current request to the limiter of a policy
 * (session-endpoints-cookie-and-csrf design.md, decision 7). It is what a controller calls when its
 * use case ends in the outcome that counts as a failure of the policy, so that the controller never
 * names the {@link RateLimiter} port and never reads the request origin itself. A bean of the
 * administrative process, registered by {@code ThrottlingConfiguration}.
 *
 * <p>A request without an origin, or whose origin has no client address, records nothing: the
 * interceptor refused it with {@code 503} before the handler ran, so there is no client to count.
 * A policy no limiter holds is a programming error and fails loudly, because a silent no-op would
 * switch layer 2 off without anyone noticing.
 */
public final class RateLimitFailures {

    private final RateLimiterRegistry limiters;

    public RateLimitFailures(RateLimiterRegistry limiters) {
        this.limiters = Objects.requireNonNull(limiters, "limiters");
    }

    /**
     * Counts one failure of {@code policy} against the client of the current request.
     *
     * @throws IllegalArgumentException when no limiter holds {@code policy}
     */
    public void record(String policy) {
        RateLimiter limiter = limiters.find(policy).orElseThrow(() ->
                new IllegalArgumentException("no limiter holds the policy '" + policy + "' (known: "
                        + limiters.policies() + ")"));
        Optional<RequestOrigin> origin = RequestOrigin.current();
        if (origin.isEmpty()) {
            return;
        }
        ClientAddress client = origin.get().clientAddress();
        if (client != null) {
            limiter.recordFailure(client);
        }
    }
}
