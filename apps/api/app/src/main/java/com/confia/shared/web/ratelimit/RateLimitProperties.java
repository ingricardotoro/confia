package com.confia.shared.web.ratelimit;

import com.confia.shared.security.RateLimitPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The values of the rate-limit policy of the administrative login, which an environment may change
 * without recompiling (web-edge-foundations design.md, decision 17). Bound through the constructor
 * so that a bad value stops the start before a request is accepted, and the message names the
 * property.
 *
 * @param requestLimit {@code request-limit}: layer 1, the requests admitted within the window
 * @param requestWindow {@code request-window}: layer 1, the sliding window
 * @param failureThreshold {@code failure-threshold}: layer 2, the failures that restrict a client
 * @param failureWindow {@code failure-window}: layer 2, how long a failure counts
 * @param restrictedInterval {@code restricted-interval}: layer 2, the least time between two
 *     attempts while restricted
 * @param restrictionCap {@code restriction-cap}: layer 2, the longest a restriction lasts
 * @param maxEntries {@code max-entries}: the clients the table holds, with headroom over the
 *     clients that are expected at once, because a full table refuses every new one
 */
@ConfigurationProperties(prefix = RateLimitProperties.PREFIX)
public record RateLimitProperties(@DefaultValue("10") int requestLimit,
        @DefaultValue("1m") Duration requestWindow, @DefaultValue("10") int failureThreshold,
        @DefaultValue("10m") Duration failureWindow,
        @DefaultValue("1m") Duration restrictedInterval,
        @DefaultValue("1h") Duration restrictionCap, @DefaultValue("50000") int maxEntries) {

    static final String PREFIX = "confia.web.rate-limit.admin-login";

    public RateLimitProperties {
        positive(requestLimit, "request-limit");
        positive(requestWindow, "request-window");
        positive(failureThreshold, "failure-threshold");
        positive(failureWindow, "failure-window");
        positive(restrictedInterval, "restricted-interval");
        positive(restrictionCap, "restriction-cap");
        positive(maxEntries, "max-entries");
        try {
            policy(requestLimit, requestWindow, failureThreshold, failureWindow,
                    restrictedInterval, restrictionCap, maxEntries);
        } catch (IllegalArgumentException invalid) {
            // The policy names a value as the code knows it ("requestLimit"); the person who set it
            // needs the property. The rest of its text, which has no client data, is kept.
            throw new IllegalArgumentException(asProperties(invalid.getMessage()), invalid);
        }
    }

    private static String asProperties(String message) {
        String named = message;
        for (String[] name : new String[][] {{"requestLimit", "request-limit"},
                {"requestWindow", "request-window"}, {"failureThreshold", "failure-threshold"},
                {"failureWindow", "failure-window"}, {"restrictedInterval", "restricted-interval"},
                {"restrictionCap", "restriction-cap"}, {"maxEntries", "max-entries"}}) {
            named = named.replace(name[0], PREFIX + "." + name[1]);
        }
        return named;
    }

    /** The policy these values describe. */
    public RateLimitPolicy policy() {
        return policy(requestLimit, requestWindow, failureThreshold, failureWindow,
                restrictedInterval, restrictionCap, maxEntries);
    }

    private static RateLimitPolicy policy(int requestLimit, Duration requestWindow,
            int failureThreshold, Duration failureWindow, Duration restrictedInterval,
            Duration restrictionCap, int maxEntries) {
        return new RateLimitPolicy(requestLimit, requestWindow, failureThreshold, failureWindow,
                restrictedInterval, restrictionCap, maxEntries);
    }

    private static void positive(int value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException(PREFIX + "." + property + " must be positive");
        }
    }

    private static void positive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(PREFIX + "." + property + " must be positive");
        }
    }
}
