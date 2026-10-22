package com.confia.shared.web.ratelimit;

import com.confia.shared.security.RateLimitPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The values of the three rate-limit policies of the administrative process, which an environment
 * may change without recompiling (web-edge-foundations design.md, decision 17; session-endpoints-
 * cookie-and-csrf design.md, decision 7): {@code confia.web.rate-limit.admin-login.*}, {@code
 * ...admin-mfa.*} and {@code ...admin-session.*}. Bound through the constructor so that a bad value
 * stops the start before a request is accepted, and the message names the whole property.
 *
 * <p>The defaults are in {@code application.yml} and not in annotations, because an annotation
 * cannot give different values to three components of the same type. A component that is absent
 * stops the start, and so does a component that is not one of the three: {@code ignoreUnknownFields
 * = false}, so a misspelled policy or limit never silently keeps a default.
 *
 * @param adminLogin the policy of the login route
 * @param adminMfa the policy of the verification and enrollment of the second factor
 * @param adminSession the policy of the refresh and the two ends of a session
 */
@ConfigurationProperties(prefix = RateLimitProperties.PREFIX, ignoreUnknownFields = false)
public record RateLimitProperties(PolicyValues adminLogin, PolicyValues adminMfa,
        PolicyValues adminSession) {

    static final String PREFIX = "confia.web.rate-limit";

    public RateLimitProperties {
        adminLogin = checked(adminLogin, "admin-login");
        adminMfa = checked(adminMfa, "admin-mfa");
        adminSession = checked(adminSession, "admin-session");
    }

    private static PolicyValues checked(PolicyValues values, String component) {
        String prefix = PREFIX + "." + component;
        if (values == null) {
            throw new IllegalArgumentException(prefix + " is not configured: the policy needs "
                    + "its limits");
        }
        values.validate(prefix);
        return values;
    }

    /**
     * The limits of one policy.
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
    public record PolicyValues(int requestLimit, Duration requestWindow, int failureThreshold,
            Duration failureWindow, Duration restrictedInterval, Duration restrictionCap,
            int maxEntries) {

        /** The policy these values describe. */
        public RateLimitPolicy policy() {
            return new RateLimitPolicy(requestLimit, requestWindow, failureThreshold,
                    failureWindow, restrictedInterval, restrictionCap, maxEntries);
        }

        /** Stops the start, naming the property under {@code prefix}, on a value the policy refuses. */
        void validate(String prefix) {
            positive(requestLimit, prefix, "request-limit");
            positive(requestWindow, prefix, "request-window");
            positive(failureThreshold, prefix, "failure-threshold");
            positive(failureWindow, prefix, "failure-window");
            positive(restrictedInterval, prefix, "restricted-interval");
            positive(restrictionCap, prefix, "restriction-cap");
            positive(maxEntries, prefix, "max-entries");
            try {
                policy();
            } catch (IllegalArgumentException invalid) {
                // The policy names a value as the code knows it ("requestLimit"); the person who
                // set it needs the property. The rest of its text, which has no client data, is kept.
                throw new IllegalArgumentException(asProperties(invalid.getMessage(), prefix),
                        invalid);
            }
        }

        private static String asProperties(String message, String prefix) {
            String named = message;
            for (String[] name : new String[][] {{"requestLimit", "request-limit"},
                    {"requestWindow", "request-window"}, {"failureThreshold", "failure-threshold"},
                    {"failureWindow", "failure-window"}, {"restrictedInterval", "restricted-interval"},
                    {"restrictionCap", "restriction-cap"}, {"maxEntries", "max-entries"}}) {
                named = named.replace(name[0], prefix + "." + name[1]);
            }
            return named;
        }

        private static void positive(int value, String prefix, String property) {
            if (value <= 0) {
                throw new IllegalArgumentException(prefix + "." + property + " must be positive");
            }
        }

        private static void positive(Duration value, String prefix, String property) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(prefix + "." + property + " must be positive");
            }
        }
    }
}
