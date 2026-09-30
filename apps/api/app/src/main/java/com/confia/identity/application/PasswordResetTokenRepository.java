package com.confia.identity.application;

import com.confia.identity.domain.PasswordResetTokenHash;
import com.confia.identity.domain.PasswordResetTokenRow;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes {@code identity_password_reset_token} (password-recovery-token design.md
 * decision 5). {@link com.confia.identity.infrastructure.JooqPasswordResetTokenRepository} is its
 * single adapter. Every instant comes from the caller's injected clock and {@code expiresAt} arrives
 * already computed, so the validity lives in one domain constant and never here.
 */
public interface PasswordResetTokenRepository {

    /** Issuances of the account strictly after {@code since}: one issued exactly at it is excluded. */
    long countIssuedSince(InstitutionId institutionId, StaffAccountId accountId, Instant since);

    /**
     * Marks every open token of the account superseded at {@code at}, an expired one included, and
     * returns how many it marked. A consumed token is left alone.
     */
    int supersedeOpen(InstitutionId institutionId, StaffAccountId accountId, Instant at);

    void insert(InstitutionId institutionId, UUID id, StaffAccountId accountId,
            PasswordResetTokenHash hash, Instant issuedAt, Instant expiresAt);

    Optional<PasswordResetTokenRow> findByHash(InstitutionId institutionId,
            PasswordResetTokenHash hash);

    /**
     * The single conditional consumption: {@code true} only when the token was still open and
     * {@code at} was strictly before its expiry. {@code false} is also how a caller learns that a
     * concurrent reset already won the token.
     */
    boolean consume(InstitutionId institutionId, UUID id, Instant at);
}
