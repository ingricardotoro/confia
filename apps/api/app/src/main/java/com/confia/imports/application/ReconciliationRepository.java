package com.confia.imports.application;

import com.confia.imports.domain.Reconciliation;
import com.confia.kernel.InstitutionId;

/**
 * Output port of the signed reconciliations of an institution. A reconciliation is only inserted and read: nothing is
 * updated or deleted, and a correction is another record.
 */
public interface ReconciliationRepository {

    void insert(InstitutionId institutionId, Reconciliation reconciliation);

    /** The reconciliations of the institution, newest first. */
    Page<Reconciliation> list(InstitutionId institutionId, PageRequest request);
}
