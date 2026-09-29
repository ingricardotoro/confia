package com.confia.identity.application;

import com.confia.identity.domain.BackoffState;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import java.time.Instant;

/**
 * Reads and writes {@code identity_mfa_totp_backoff}'s state (column-encryption-and-mfa-totp
 * design.md, decision 8; specs/identity/spec.md, requirement "Límite de tasa sobre la verificación
 * de código TOTP"). Keyed by {@link StaffAccountId}, not by an {@link
 * com.confia.identity.domain.IdentifierFingerprint} as {@link LoginBackoffStore} is: TOTP
 * verification always runs against an account already identified by a correct password, so there
 * is no identifier-without-account oracle to protect here (design.md, decision 8).
 *
 * <p>{@link com.confia.identity.infrastructure.JooqTotpVerificationBackoffStore} is its real
 * adapter, reusing {@link com.confia.identity.domain.BackoffPolicy}/{@link BackoffState} without
 * any new constant.
 */
public interface TotpVerificationBackoffStore {

    /**
     * Creates or locks the row for {@code accountId} and returns the state it held before this
     * call (design.md, decision 8, same reclaim shape as {@link LoginBackoffStore#claim}).
     */
    BackoffState claim(InstitutionId institutionId, StaffAccountId accountId, Instant now);

    void save(InstitutionId institutionId, StaffAccountId accountId, BackoffState state);
}
