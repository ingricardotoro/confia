package com.confia.shared.web.ratelimit;

import com.confia.shared.security.RateLimiter;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The limiters of the process by policy name (web-edge-foundations design.md, decision 17). One
 * limiter serves one policy, so that filling the table of one never shuts out another. The map is
 * copied and never changes after the start.
 */
public final class RateLimiterRegistry {

    private final Map<String, RateLimiter> limiters;

    public RateLimiterRegistry(Map<String, RateLimiter> limiters) {
        this.limiters = Map.copyOf(limiters);
    }

    /** The limiter of {@code policy}, or empty for a name nobody registered. */
    public Optional<RateLimiter> find(String policy) {
        return Optional.ofNullable(limiters.get(policy));
    }

    /** The names of the registered policies. */
    public Set<String> policies() {
        return limiters.keySet();
    }
}
