package com.confia.shared.web.delay;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How many required delays the process waits for at once, and for how long at most
 * (web-edge-foundations design.md, decision 18; session-endpoints-cookie-and-csrf design.md,
 * decision 8). Bound through the constructor so that a bad value stops the start before a request
 * is accepted, and the message names the property. Unknown fields stop the start too: a misspelled
 * limit must never silently keep the default.
 *
 * @param maxConcurrentWaits {@code max-concurrent-waits}: the permits of the semaphore, which is
 *     the most waits in progress; one more request is refused with {@code 503}. At most half of
 *     {@code server.tomcat.max-connections}, which {@code RequiredDelayConfiguration} checks
 * @param maxWait {@code max-wait}: the longest the materializer waits for one answer, 30 seconds
 *     by default, positive and at most {@value #LARGEST_MAX_WAIT_SECONDS} seconds. It is below the
 *     time at which a proxy in front of the process cuts the connection, so a delay the policy
 *     computes in minutes never turns into a cut connection
 */
@ConfigurationProperties(prefix = DelayProperties.PREFIX, ignoreUnknownFields = false)
public record DelayProperties(@DefaultValue("200") int maxConcurrentWaits,
        @DefaultValue("30s") Duration maxWait) {

    static final String PREFIX = "confia.web.delay";

    /** The name of the property of the permits, as a person sets it. */
    public static final String MAX_CONCURRENT_WAITS = PREFIX + ".max-concurrent-waits";

    /** The name of the property of the cap of the wait, as a person sets it. */
    public static final String MAX_WAIT = PREFIX + ".max-wait";

    /** The largest cap a start accepts, in seconds. */
    static final long LARGEST_MAX_WAIT_SECONDS = 60;

    /** The longest cap a start accepts. */
    static final Duration LARGEST_MAX_WAIT = Duration.ofSeconds(LARGEST_MAX_WAIT_SECONDS);

    /** The cap of the materializers that are not given one: the default of the property. */
    public static final Duration DEFAULT_MAX_WAIT = Duration.ofSeconds(30);

    public DelayProperties {
        if (maxConcurrentWaits <= 0) {
            throw new IllegalArgumentException(MAX_CONCURRENT_WAITS + " must be positive");
        }
        requireValidCap(maxWait);
    }

    /** Stops on a cap that is not positive or is above the largest one, naming the property. */
    static void requireValidCap(Duration maxWait) {
        if (maxWait == null || maxWait.isZero() || maxWait.isNegative()
                || maxWait.compareTo(LARGEST_MAX_WAIT) > 0) {
            throw new IllegalArgumentException(MAX_WAIT + " must be positive and at most "
                    + LARGEST_MAX_WAIT_SECONDS + " seconds");
        }
    }
}
