package com.confia.imports.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The record of a simulation or an application of a file: counts and the fingerprint of the file, nothing of its
 * content. Every data row is exactly one of valid (created, or it would be), rejected (a defect) or skipped (imported
 * before), so {@code totalRows = validRows + rejectedRows + skippedRows}; a simulation creates nothing and an
 * application has {@code createdRows = validRows}.
 */
public record ImportBatch(UUID id, ImportKind kind, ImportStatus status, String fileSha256, int totalRows,
        int validRows, int rejectedRows, int createdRows, int skippedRows, UUID simulationId, UUID createdBy,
        Instant createdAt, Instant appliedAt) {

    public ImportBatch {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(fileSha256, "fileSha256");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        if (totalRows < 0 || validRows < 0 || rejectedRows < 0 || createdRows < 0 || skippedRows < 0
                || totalRows != validRows + rejectedRows + skippedRows) {
            throw new IllegalArgumentException("the counts of a batch add up to its total");
        }
        if (status == ImportStatus.VALIDATED && (createdRows != 0 || appliedAt != null || simulationId != null)) {
            throw new IllegalArgumentException("a simulation creates nothing");
        }
        if (status == ImportStatus.APPLIED && (createdRows != validRows || appliedAt == null)) {
            throw new IllegalArgumentException("an application created what it says was valid");
        }
    }

    /** A simulation: {@code validRows} would be created. */
    public static ImportBatch simulated(UUID id, ImportKind kind, String fileSha256, int totalRows, int validRows,
            int rejectedRows, int skippedRows, UUID createdBy, Instant now) {
        return new ImportBatch(id, kind, ImportStatus.VALIDATED, fileSha256, totalRows, validRows, rejectedRows, 0,
                skippedRows, null, createdBy, now, null);
    }

    /** An application of the file simulated in {@code simulationId}: {@code createdRows} students were registered. */
    public static ImportBatch applied(UUID id, ImportKind kind, String fileSha256, int totalRows, int createdRows,
            int rejectedRows, int skippedRows, UUID simulationId, UUID createdBy, Instant now) {
        return new ImportBatch(id, kind, ImportStatus.APPLIED, fileSha256, totalRows, createdRows, rejectedRows,
                createdRows, skippedRows, simulationId, createdBy, now, now);
    }
}
