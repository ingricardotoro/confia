package com.confia.charges.application;

import com.confia.kernel.InstitutionId;
import java.util.Set;
import java.util.UUID;

/** Output port of the items of the runs. Items are inserted and read; none is updated or deleted (INV-47). */
public interface AccrualRunItemRepository {

    void insertAll(InstitutionId institution, UUID runId, java.util.List<RunItem> items);

    /** The students that already have items in the run: what a resumed run skips. */
    Set<UUID> processedStudents(InstitutionId institution, UUID runId);

    RunItemCounts counts(InstitutionId institution, UUID runId);

    Page<RunItem> page(InstitutionId institution, UUID runId, int page, int size);

    /** The items counted by outcome and the distinct students they cover. */
    record RunItemCounts(int students, int created, int existing, int failed) {
    }
}
