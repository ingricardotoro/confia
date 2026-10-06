package com.confia.shared.web.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a handler method as a write that moves money, so that it requires the {@code
 * Idempotency-Key} header (web-edge-foundations design.md, decision 19). {@link
 * IdempotencyKeyInterceptor} validates the header before the controller runs, and {@link
 * IdempotentRequestHandler} then runs the use case under the key. A method without the annotation
 * never requires the header, so a read-only endpoint is not affected.
 *
 * <p><b>On a method, not on a class</b>, like {@code @RateLimited}: the interceptor reads it from
 * the handler method alone, so an annotation on a controller class is a compile error and would
 * never enforce anything.
 *
 * <p><b>Never on an endpoint of {@code identity}.</b> The executor stores the body of the response
 * and replays it, so the tokens of a sign-in would be stored in the clear. Rule W5 ({@code
 * IdempotencyNotInIdentityTest}) breaks the build on it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface IdempotentWrite {
}
