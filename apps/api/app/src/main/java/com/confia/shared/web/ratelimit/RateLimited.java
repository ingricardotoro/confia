package com.confia.shared.web.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Puts a handler method under the rate limit of a named policy (web-edge-foundations design.md,
 * decision 17). {@link RateLimitInterceptor} asks the limiter of {@link #policy()} before the
 * controller runs, so a request that is over its limit opens no transaction, hashes no password
 * and records no failure. A name no limiter holds stops the start of the process, naming it.
 *
 * <p><b>On a method, not on a class.</b> The annotation targets methods only, and the interceptor
 * reads it from the handler method alone: an annotation on a controller class is a compile error
 * and would never limit anything.
 *
 * <p><b>Never on an asynchronous handler</b> (one that returns a {@code Callable}, a {@code
 * DeferredResult}, a {@code CompletionStage} or any other asynchronous value) until that handler
 * has a test of its own over the real chain. The interceptor evaluates the {@code REQUEST}
 * dispatch only and lets the {@code ASYNC} dispatch of the same request through, because the
 * limiter was already asked once for it; that is correct for a handler that has been proven to be
 * dispatched exactly that way, and nothing else has.
 *
 * <p>For the same reason <b>no filter may call {@code startAsync} and dispatch to a limited
 * handler</b>: that {@code ASYNC} dispatch would reach the handler without any {@code REQUEST}
 * dispatch having asked the limiter.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimited {

    /** The policy that limits the method, such as {@code admin-login}. */
    String policy();
}
