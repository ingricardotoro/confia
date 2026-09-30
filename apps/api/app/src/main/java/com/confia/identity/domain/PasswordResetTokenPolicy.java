package com.confia.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The single source of every password-reset token constant (password-recovery-token design.md
 * decision 4): the 30-minute validity, the 60-minute issuance window and its limit of three, and the
 * order in which a stored token's rejection reasons are reported. It has no clock of its own and
 * receives every instant, as {@link BackoffPolicy} does. The adapter never computes {@code
 * expires_at}: it receives {@link #expiresAt}, so the thirty minutes are written once in the code.
 */
public final class PasswordResetTokenPolicy {

    public static final Duration VALIDITY = Duration.ofMinutes(30);
    public static final Duration ISSUANCE_WINDOW = Duration.ofMinutes(60);
    public static final int MAX_ISSUANCES_PER_WINDOW = 3;

    public Instant expiresAt(Instant issuedAt) {
        return Objects.requireNonNull(issuedAt, "issuedAt").plus(VALIDITY);
    }

    /**
     * The exclusive start of the rolling window at {@code now}: an issuance counts only when it is
     * strictly after this instant, so one issued exactly sixty minutes earlier no longer counts.
     */
    public Instant issuanceWindowStart(Instant now) {
        return Objects.requireNonNull(now, "now").minus(ISSUANCE_WINDOW);
    }

    public boolean allowsAnotherIssuance(long issuedInWindow) {
        return issuedInWindow < MAX_ISSUANCES_PER_WINDOW;
    }

    /**
     * Empty when the token may still be used at {@code now}; otherwise the first reason in the order
     * consumed, superseded, expired. A token is valid only strictly before its expiry, so the
     * 30:00 border is outside. {@link PasswordResetRejectionReason#TOKEN_NOT_FOUND} is the use case's
     * to decide, when there is no row at all.
     */
    public Optional<PasswordResetRejectionReason> rejectionReasonOf(PasswordResetTokenRow row,
            Instant now) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(now, "now");
        if (row.consumedAt() != null) {
            return Optional.of(PasswordResetRejectionReason.TOKEN_CONSUMED);
        }
        if (row.supersededAt() != null) {
            return Optional.of(PasswordResetRejectionReason.TOKEN_SUPERSEDED);
        }
        if (!now.isBefore(row.expiresAt())) {
            return Optional.of(PasswordResetRejectionReason.TOKEN_EXPIRED);
        }
        return Optional.empty();
    }
}
