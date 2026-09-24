package com.confia.shared.security;

import com.confia.kernel.DomainException;
import java.util.Objects;

/**
 * A concurrent request collided on the same idempotency marker, and the collision itself is not
 * treatable as a repeatable response (design.md, decision 5, decision 9). Two reasons share this
 * single stable code, because they are two observable moments of the same collision, not two
 * different business conditions:
 *
 * <ul>
 *   <li>{@link Reason#WAIT_EXHAUSTED}: the {@code lock_timeout} bounding the wait for a marker row
 *       still held by another open transaction expired before that transaction finished (the
 *       adapter's {@code translate(...)}, design.md section 6.3, raises this for {@code SQLState
 *       55P03}). The web layer will translate this to {@code 409} once it exists (change 7).
 *   <li>{@link Reason#MARKER_IN_PROGRESS}: a defensive branch — a marker row is confirmed {@code
 *       IN_PROGRESS} from a transaction other than the caller's own, which should never happen once
 *       the marker and its business effect always share one transaction (design.md section 6, the
 *       component's flow), but is named rather than left to fail unexplained if it ever does.
 * </ul>
 */
public final class IdempotencyConflictException extends DomainException {

    public static final String CODE = "idempotency-conflict";

    public enum Reason {
        WAIT_EXHAUSTED,
        MARKER_IN_PROGRESS
    }

    private final Reason reason;

    public IdempotencyConflictException(Reason reason) {
        this(reason, null);
    }

    /**
     * @param cause the original error, kept for diagnostics — never {@code null} when the adapter
     *     raises this from a translated {@code SQLException} (design.md section 6.3); {@code null}
     *     is only valid for the defensive {@link Reason#MARKER_IN_PROGRESS} branch, which has no
     *     underlying {@code SQLException} to attach.
     */
    public IdempotencyConflictException(Reason reason, Throwable cause) {
        super(CODE, "idempotency marker conflict: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
        if (cause != null) {
            initCause(cause);
        }
    }

    public Reason reason() {
        return reason;
    }
}
