package com.confia.identity.application;

import com.confia.kernel.InstitutionId;
import java.time.Instant;

/**
 * What the identity purges need from the database (background-jobs-with-db-scheduler design.md
 * decision 17; ADR-0015). Every operation runs inside the transaction the caller opened, with the
 * {@code system} actor and the institution of the task in its context. The adapter is the only place that
 * names the {@code SECURITY DEFINER} functions of the purge, which are the only way to delete a row of
 * these tables.
 *
 * <p>The caller computes every cutoff from its clock. The functions also keep a floor of their own
 * and never delete a row younger than it, whatever cutoff they are given.
 */
public interface IdentityRetentionPurges {

    /**
     * Deletes at most {@code limit} password-reset tokens of {@code institution} that expired before
     * {@code cutoff}, and returns how many it deleted.
     */
    long purgePasswordResetTokens(InstitutionId institution, Instant cutoff, int limit);
}
