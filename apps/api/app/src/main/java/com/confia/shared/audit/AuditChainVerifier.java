package com.confia.shared.audit;

import com.confia.kernel.InstitutionId;
import java.time.Instant;

/**
 * Recalculates {@code shared_audit_log}'s hash chain and compares it against what is stored
 * (design.md decision 11 and background-jobs-with-db-scheduler design.md decision 19;
 * specs/audit-trail/spec.md, "Contrato observable del verificador de cadena"). A routine that can be
 * called directly, with no scheduling infrastructure: the daily and weekly cadences of {@code
 * AuditChainVerificationJobs} call it, and so can a test or an operator.
 */
public interface AuditChainVerifier {

    /** Recalculates the whole chain of {@code institutionId} from its genesis record. */
    AuditChainVerification verifyChainOf(InstitutionId institutionId);

    /**
     * Recalculates the chain of {@code institutionId} from the first row whose {@code occurred_at} is at or after
     * {@code since}, and then every later row by {@code id}. The walk is by {@code id} and not filtered by {@code
     * occurred_at} again: a hash chain must not skip a link, so when {@code occurred_at} is not monotonic in {@code
     * id} a later row older than {@code since} is still recalculated and counted in {@code verifiedRows}. It takes
     * the <em>stored</em> {@code prev_hash} of the first row as the running hash, so the link between that row and
     * the one before it is not checked here (the weekly cadence, which walks the whole chain, covers it), but the
     * {@code row_hash} of that first row is recalculated like any other. Without a row in the window the result is
     * {@link AuditChainVerification.Empty}.
     */
    AuditChainVerification verifySince(InstitutionId institutionId, Instant since);
}
