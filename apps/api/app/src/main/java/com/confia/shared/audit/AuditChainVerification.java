package com.confia.shared.audit;

import com.confia.kernel.InstitutionId;

/**
 * The result of {@link AuditChainVerifier#verifyChainOf(InstitutionId)} (design.md decision 11).
 * {@code sealed} with three cases so a caller — the future cambio 9, which owns scheduling this
 * against {@code confia_audit_chain_verified_rows} and the S1 alert — must handle "no activity",
 * "integral" and "divergent" as three genuinely different outcomes, never collapse "integral" and
 * "empty" into one misleading {@code true}/{@code false}.
 */
public sealed interface AuditChainVerification {

    /** No row exists for {@code institutionId}: there is no chain to verify. */
    record Empty(InstitutionId institutionId) implements AuditChainVerification {
    }

    /**
     * Every row recalculated exactly as stored. {@code verifiedRows} feeds
     * {@code confia_audit_chain_verified_rows} (docs/03-seguridad.md §12.4); {@code lastRowHash} is
     * the running hash after the last row, which a future incremental verifier (cambio 9) could
     * resume from.
     */
    record Intact(InstitutionId institutionId, long verifiedRows, byte[] lastRowHash)
            implements AuditChainVerification {
    }

    /**
     * The first divergent row, identified by the full primary key — {@code institutionId} plus
     * {@code firstDivergentId} — never a mere offset (F0 exit criterion 3;
     * specs/audit-trail/spec.md, "Contrato observable del verificador de cadena"). The walk stops
     * here: it never continues reporting a second divergence that is only a consequence of the
     * first.
     */
    record Diverged(InstitutionId institutionId, long verifiedRows, long firstDivergentId,
            Divergence divergence, byte[] expectedHash, byte[] storedHash)
            implements AuditChainVerification {
    }

    /**
     * The four ways a row can diverge (design.md decision 11). A runbook needs this distinction: a
     * {@code ROW_HASH_MISMATCH} means a field of the row itself was altered; a
     * {@code PREV_HASH_MISMATCH} or a genesis-specific mismatch means a row was inserted, deleted,
     * or reordered around the divergent one.
     */
    enum Divergence {
        /** The very first row fetched for the institution is not {@code id = 1}. */
        MISSING_GENESIS,
        /** The first row is {@code id = 1}, but its {@code prev_hash} is not 32 zero bytes. */
        GENESIS_PREV_HASH_MISMATCH,
        /** A non-first row's {@code prev_hash} does not equal the running hash. */
        PREV_HASH_MISMATCH,
        /** The row's own recalculated hash does not equal its stored {@code row_hash}. */
        ROW_HASH_MISMATCH
    }
}
