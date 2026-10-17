package com.confia.identity.application;

import com.confia.identity.domain.RefreshTokenFamily;
import com.confia.identity.domain.RefreshTokenFamilyState;
import com.confia.kernel.InstitutionId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Where session families are written and read (session-tokens-and-web-layer design.md decisions 9
 * and 10). It never opens a transaction: it runs inside the one the use case opened. {@link
 * com.confia.identity.infrastructure.JooqRefreshTokenFamilyRepository} is its adapter. Reuse and
 * revocation add their own operations in the changes that deliver them.
 */
public interface RefreshTokenFamilyRepository {

    /** Writes a new, unrevoked family. */
    void insert(InstitutionId institutionId, RefreshTokenFamily family);

    /**
     * Takes the row lock of the family ({@code SELECT ... FOR UPDATE}) and returns its state. The lock
     * is the point of serialization of every rotation and revocation of the family, and the state
     * returned is the one the locking statement itself reads: under READ COMMITTED a statement that
     * waited for the lock sees what the holder committed, so a family revoked while the caller waited
     * comes back revoked. A decision must be taken on this state, never on one read before the lock
     * (review of PR 9, I-2). Empty when the family does not exist for the institution.
     */
    Optional<RefreshTokenFamilyState> lockById(InstitutionId institutionId, UUID familyId);

    /** Records a use of the family: {@code last_used_at} moves to {@code now}. */
    void recordUse(InstitutionId institutionId, UUID familyId, Instant now);
}
