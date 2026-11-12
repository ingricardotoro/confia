package com.confia.imports.application;

import com.confia.imports.domain.ImportBatch;
import java.time.Instant;
import java.util.UUID;

/** A batch as the web layer may see it: counts and the fingerprint of the file, never a value of it. */
public record ImportBatchView(UUID id, String kind, String status, String fileSha256, int totalRows, int validRows,
        int rejectedRows, int createdRows, int skippedRows, Instant createdAt, Instant appliedAt) {

    static ImportBatchView of(ImportBatch batch) {
        return new ImportBatchView(batch.id(), batch.kind().name(), batch.status().name(), batch.fileSha256(),
                batch.totalRows(), batch.validRows(), batch.rejectedRows(), batch.createdRows(),
                batch.skippedRows(), batch.createdAt(), batch.appliedAt());
    }
}
