package com.confia.imports.application;

import com.confia.imports.domain.ImportBatch;
import com.confia.imports.domain.ImportKind;
import com.confia.kernel.InstitutionId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Output port of the batches of an institution. A batch is only inserted and read: it holds counts and the fingerprint
 * of a file, and nothing is updated or deleted.
 */
public interface ImportBatchRepository {

    void insert(InstitutionId institutionId, ImportBatch batch);

    /** The newest simulation of this fingerprint by this account, if any. */
    Optional<ImportBatch> findValidated(InstitutionId institutionId, UUID createdBy, ImportKind kind,
            String fileSha256);

    /** The newest batch of any status by this account, if any. */
    Optional<ImportBatch> latestOf(InstitutionId institutionId, UUID createdBy, ImportKind kind);

    /** The batches of the institution of the given kinds, newest first. */
    Page<ImportBatch> list(InstitutionId institutionId, PageRequest request, Set<ImportKind> kinds);

    /**
     * What the applications of each kind left, for the reconciliation: the rows created by every {@code APPLIED} batch
     * (summed) and the rows the newest {@code APPLIED} batch left rejected (not summed: what was corrected and applied
     * again would be counted twice). A kind never applied is absent.
     */
    Map<ImportKind, AppliedTotals> appliedTotals(InstitutionId institutionId);

    /** The sum of the created rows of the applications of a kind and the rejected rows of the newest one. */
    record AppliedTotals(int created, int rejected) {
    }
}
