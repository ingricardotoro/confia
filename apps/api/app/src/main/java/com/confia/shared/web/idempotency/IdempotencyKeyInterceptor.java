package com.confia.shared.web.idempotency;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.List;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Validates the {@code Idempotency-Key} header of an {@link IdempotentWrite} method before the
 * controller runs (web-edge-foundations design.md, decision 19). It acts in {@code preHandle} and
 * nowhere else: a refusal is an exception thrown here, so the controller, the use case and the
 * transaction behind it never execute and the one translator of exceptions writes the answer.
 *
 * <ul>
 *   <li>absent, empty or blank: {@link IdempotencyKeyMissingException}, {@code 400
 *       idempotency-key-missing};
 *   <li>repeated, longer than {@value #MAX_KEY_LENGTH} characters or with a character outside
 *       visible ASCII ({@code 0x21} to {@code 0x7E}, which excludes any space): {@link
 *       IdempotencyKeyInvalidException}, {@code 400 validation-failed}.
 * </ul>
 *
 * <p>{@value #MAX_KEY_LENGTH} is below the technical limit of 200 of the marker table and holds a
 * UUID with room to spare; the character set keeps a key from forging a line of a log. The accepted
 * value is left on the request for {@link IdempotentRequestHandler}, so the handler never reads the
 * header a second time and a method that does not declare the annotation can never reach it with
 * a key nobody validated. A handler that is not an {@link IdempotentWrite} method is not touched.
 *
 * <p>Only the {@code REQUEST} dispatch is evaluated: the {@code ASYNC} dispatch of the same request
 * has been validated already.
 */
public final class IdempotencyKeyInterceptor implements HandlerInterceptor {

    /** The request header that carries the key. */
    public static final String HEADER = "Idempotency-Key";

    /** The longest key accepted, in characters. */
    public static final int MAX_KEY_LENGTH = 128;

    /** The request attribute that holds the validated key for {@link IdempotentRequestHandler}. */
    static final String KEY_ATTRIBUTE = IdempotencyKeyInterceptor.class.getName() + ".KEY";

    private static final char FIRST_VISIBLE_ASCII = 0x21;
    private static final char LAST_VISIBLE_ASCII = 0x7E;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) {
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        if (!(handler instanceof HandlerMethod method)
                || method.getMethodAnnotation(IdempotentWrite.class) == null) {
            return true;
        }
        List<String> values = Collections.list(request.getHeaders(HEADER));
        if (values.isEmpty()) {
            throw new IdempotencyKeyMissingException();
        }
        if (values.size() > 1) {
            throw new IdempotencyKeyInvalidException("the header is repeated");
        }
        String key = values.get(0);
        if (key.isBlank()) {
            throw new IdempotencyKeyMissingException();
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IdempotencyKeyInvalidException("the key is longer than " + MAX_KEY_LENGTH
                    + " characters");
        }
        if (!key.chars().allMatch(IdempotencyKeyInterceptor::isVisibleAscii)) {
            throw new IdempotencyKeyInvalidException(
                    "the key has a character outside visible ASCII");
        }
        request.setAttribute(KEY_ATTRIBUTE, key);
        return true;
    }

    private static boolean isVisibleAscii(int character) {
        return character >= FIRST_VISIBLE_ASCII && character <= LAST_VISIBLE_ASCII;
    }
}
