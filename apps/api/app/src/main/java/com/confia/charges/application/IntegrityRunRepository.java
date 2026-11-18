package com.confia.charges.application;

import com.confia.charges.domain.IntegrityFinding;
import com.confia.charges.domain.IntegrityRun;
import com.confia.kernel.InstitutionId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Output port of the results of the nightly integrity check. It inserts and reads; a result is never edited or deleted. */
public interface IntegrityRunRepository {

    /** Records the run and its confirmed discrepancies together. */
    void insert(InstitutionId institution, IntegrityRun run, List<IntegrityFinding> findings);

    /** The runs of the institution, most recent first. */
    Page<IntegrityRun> list(InstitutionId institution, int page, int size);

    Optional<IntegrityRun> latest(InstitutionId institution);

    Optional<IntegrityRun> findById(InstitutionId institution, UUID runId);

    List<IntegrityFinding> findings(InstitutionId institution, UUID runId);
}
