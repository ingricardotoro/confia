package com.confia.shared.web.delay;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How many required delays the process waits for at once (web-edge-foundations design.md, decision
 * 18). Bound through the constructor so that a bad value stops the start before a request is
 * accepted, and the message names the property. Unknown fields stop the start too: a misspelled
 * limit must never silently keep the default.
 *
 * @param maxConcurrentWaits {@code max-concurrent-waits}: the permits of the semaphore, which is
 *     the most waits in progress; one more request is refused with {@code 503}. At most half of
 *     {@code server.tomcat.max-connections}, which {@code RequiredDelayConfiguration} checks
 */
@ConfigurationProperties(prefix = DelayProperties.PREFIX, ignoreUnknownFields = false)
public record DelayProperties(@DefaultValue("200") int maxConcurrentWaits) {

    static final String PREFIX = "confia.web.delay";

    /** The name of the property of the permits, as a person sets it. */
    public static final String MAX_CONCURRENT_WAITS = PREFIX + ".max-concurrent-waits";

    public DelayProperties {
        if (maxConcurrentWaits <= 0) {
            throw new IllegalArgumentException(MAX_CONCURRENT_WAITS + " must be positive");
        }
    }
}
