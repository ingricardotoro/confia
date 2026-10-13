package com.confia.identity.application;

import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import java.time.Instant;

/**
 * Revokes every live session family of one account (session-tokens-and-web-layer design.md
 * decision 12, hard condition H2). It never opens a transaction: it runs inside the one the caller
 * already opened, so the revocation commits or rolls back together with the password change that
 * asked for it. {@link com.confia.identity.infrastructure.JooqAccountSessionRevoker} is its adapter.
 */
public interface AccountSessionRevoker {

    /**
     * Revokes the account's families that are not yet revoked, and empties the encrypted
     * successors of the tokens of those families. A family that was already revoked is left as it
     * is, with its own instant and reason.
     *
     * @return how many families this call revoked, which is zero when there was nothing to revoke
     */
    int revokeAllLive(InstitutionId institutionId, StaffAccountId accountId,
            SessionRevocationReason reason, Instant now);
}
