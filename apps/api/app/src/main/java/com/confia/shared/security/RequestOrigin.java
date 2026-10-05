package com.confia.shared.security;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the request being served came from, for the audit trail (web-edge-foundations design.md,
 * decisions 11 and 13). The request filter binds one for the whole of a request in {@link #CURRENT}
 * and the audit writer reads it. A {@link ScopedValue} is immutable, exists only while the filter's
 * call runs and cannot be seen from another request even when the thread is reused, which a
 * thread-local cannot promise and which only shows under concurrency. The origin is not inherited
 * by work handed to a plain executor (only a {@code StructuredTaskScope} forks inherit it), so code
 * must not rely on it outside the request thread.
 *
 * @param requestId the id the server generated for the request, never one the client sent
 * @param clientAddress the resolved client address, or {@code null} when the container reported no
 *     remote address that is an IP literal
 * @param userAgent the {@code User-Agent} header cut to its limit and without control characters,
 *     or {@code null} when the request had none
 */
public record RequestOrigin(UUID requestId, ClientAddress clientAddress, String userAgent) {

    /** Bound by the request filter around the rest of the filter chain; unbound elsewhere. */
    public static final ScopedValue<RequestOrigin> CURRENT = ScopedValue.newInstance();

    public RequestOrigin {
        Objects.requireNonNull(requestId, "requestId");
    }

    /** The origin of the request this thread is serving, empty outside a request. */
    public static Optional<RequestOrigin> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }
}
