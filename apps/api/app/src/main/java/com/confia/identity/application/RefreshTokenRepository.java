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
 * com.confia.identity.infrastructure.JooqRefreshTokenRepository} is its adapter. It stores and reads
 * the encrypted successor as an opaque string and never decrypts it: that is {@link RotateRefreshToken}'s,
 * and only inside the grace window (decision 11).
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
     * mechanism. The successor is inserted afterwards: its key is deferred. The encrypted successor is
     * written by this same statement, and the V8 trigger lets it be written only once.
     *
     * @param successorCiphertext the successor's text, encrypted with the row of {@code tokenId} as
     *     its additional authenticated data; never {@code null}
     */
    boolean consume(InstitutionId institutionId, UUID tokenId, Instant now,
            UserAgentFingerprint presenter, UUID successorId, String successorCiphertext);

    /**
     * Reads the encrypted successor of a consumed token together with the expiry of the token that
     * replaced it, or nothing when the token has none (it is open, or the value was emptied). It
     * reads a value only; whether it may be decrypted is the decision's, not the adapter's.
     */
    Optional<EncryptedSuccessor> findEncryptedSuccessor(InstitutionId institutionId, UUID tokenId);

    /**
     * Empties the encrypted successor of every token of the family that was consumed at or before
     * {@code cutoff}, which the caller derives from the grace window; later ones are kept.
     */
    void clearSuccessorsConsumedAtOrBefore(InstitutionId institutionId, UUID familyId, Instant cutoff);

    /** Empties the encrypted successor of every token of the family that still holds one. */
    void clearSuccessors(InstitutionId institutionId, UUID familyId);
}
