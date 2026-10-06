package com.confia.shared.web.delay;

import java.time.Duration;

/**
 * The wait of {@link RequiredDelayMaterializer}, as a port so a test can hold and release it with
 * a latch instead of a clock (web-edge-foundations design.md, decision 18). The materializer's own
 * default blocks the current virtual thread with {@code Thread.sleep}.
 */
@FunctionalInterface
public interface DelayTimer {

    /**
     * Returns once {@code delay} has passed.
     *
     * @throws InterruptedException if the thread is interrupted first
     */
    void await(Duration delay) throws InterruptedException;
}
