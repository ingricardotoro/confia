package com.confia.identity.application;

import com.confia.identity.domain.AuthenticationResult;
import java.time.Duration;
import java.util.Objects;

/**
 * Result of the password-authentication use case, and the wait the caller MUST honor before
 * responding (design.md, decision 1; §6.2).
 *
 * <p>{@code requiredDelay} is counted from the moment this record is returned, with the
 * transaction already committed. The caller: (1) does not deliver the response before it elapses —
 * added to, never absorbing, any time already spent on Argon2id, or the timing side channel §4.6
 * exists to close would reopen from the back; (2) never materializes the wait inside any
 * transaction — guaranteed by construction, since the use case commits before returning; (3)
 * never retains a platform thread while waiting; (4) bounds the number of simultaneously delayed
 * responses and, on reaching that limit, responds with a uniform capacity error decided before
 * processing the attempt, never shortening the wait to make room; (5) produces the same
 * observable response for both rejection reasons; (6) abandons the wait if the client closes the
 * connection — no effect depends on it, since the counter already advanced and the audit entry is
 * already written.
 *
 * <p>The backoff delays the response and does NOT deny it: {@code requiredDelay} may be positive
 * alongside an {@link AuthenticationResult.Authenticated} outcome (specs/identity/spec.md, "Una
 * contraseña correcta durante el retroceso tiene éxito tras el retardo").
 */
public record AuthenticationDecision(AuthenticationResult result, Duration requiredDelay) {

    public AuthenticationDecision {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(requiredDelay, "requiredDelay");
        if (requiredDelay.isNegative()) {
            throw new IllegalArgumentException("requiredDelay must not be negative, was "
                    + requiredDelay);
        }
    }
}
