package com.confia.identity.application;

import com.confia.identity.domain.RefreshTokenHash;
import com.confia.identity.domain.RefreshTokenRow;
import com.confia.identity.domain.RefreshTokenState;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.kernel.InstitutionId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Where refresh tokens are written and read (session-tokens-and-web-layer design.md decisions 9 and
 * 10). It never opens a transaction: it runs inside the one the use case opened. {@link
 * com.confia.identity.infrastructure.JooqRefreshTokenRepository} is its adapter. The grace window
 * adds its own operations in the change that delivers it.
 */
public interface RefreshTokenRepository {

    /** Writes a new, unconsumed token of an existing family. */
    void insert(InstitutionId institutionId, RefreshTokenRow row);

    /** Finds a token by the hash of its text, without a lock; another institution's is not visible. */
    Optional<RefreshTokenState> findByHash(InstitutionId institutionId, RefreshTokenHash hash);

    /**
     * Reads a token again with a new statement, after the lock of its family was taken: under READ
     * COMMITTED that statement sees what the previous holder of the lock committed.
     */
    Optional<RefreshTokenState> findById(InstitutionId institutionId, UUID tokenId);

    /**
     * Consumes an open token at {@code now}, recording who presented it and which token replaced it,
     * and reports whether a row was affected. The update is conditional on the token being open, so
     * under the lock of the family {@code false} cannot happen; it is the safety net, not the
     * mechanism. The successor is inserted afterwards: its key is deferred.
     */
    boolean consume(InstitutionId institutionId, UUID tokenId, Instant now,
            UserAgentFingerprint presenter, UUID successorId);

    /** Empties the encrypted successor of every token of the family that still holds one. */
    void clearSuccessors(InstitutionId institutionId, UUID familyId);
}
