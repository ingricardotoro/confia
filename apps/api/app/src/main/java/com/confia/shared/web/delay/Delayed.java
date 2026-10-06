package com.confia.shared.web.delay;

import java.time.Duration;
import java.util.Objects;

/**
 * What a use case hands back to the edge when its answer must be delayed (web-edge-foundations
 * design.md, decision 18): the value of the answer and the time the edge waits, counted from the
 * moment the use case returns, before it sends the answer. The use case computes the delay and the
 * edge materializes it, so no {@code application} or {@code domain} class ever waits.
 *
 * @param value what the edge answers once the delay has passed
 * @param requiredDelay how long to wait first; zero or negative means no wait
 * @param <T> the type of the value
 */
public record Delayed<T>(T value, Duration requiredDelay) {

    public Delayed {
        Objects.requireNonNull(requiredDelay, "requiredDelay");
    }
}
