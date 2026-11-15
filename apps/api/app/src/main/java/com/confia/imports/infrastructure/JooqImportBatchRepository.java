package com.confia.imports.infrastructure;

import static confia.generated.jooq.tables.ImportsBatch.IMPORTS_BATCH;

import com.confia.imports.application.ImportBatchRepository;
import com.confia.imports.application.Page;
import com.confia.imports.application.PageRequest;
import com.confia.imports.domain.ImportBatch;
import com.confia.imports.domain.ImportKind;
import com.confia.imports.domain.ImportStatus;
import com.confia.kernel.InstitutionId;
import confia.generated.jooq.tables.records.ImportsBatchRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/**
 * The jOOQ adapter of {@link ImportBatchRepository} over {@code imports_batch}. It only inserts and reads: the table
 * grants the application role nothing else, and the batch holds counts and a fingerprint, never the content of a file.
 */
public final class JooqImportBatchRepository implements ImportBatchRepository {

    private final DSLContext dsl;

    public JooqImportBatchRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institutionId, ImportBatch batch) {
        dsl.insertInto(IMPORTS_BATCH)
                .set(IMPORTS_BATCH.INSTITUTION_ID, institutionId.value())
                .set(IMPORTS_BATCH.ID, batch.id())
                .set(IMPORTS_BATCH.KIND, batch.kind().name())
                .set(IMPORTS_BATCH.STATUS, batch.status().name())
                .set(IMPORTS_BATCH.FILE_SHA256, batch.fileSha256())
                .set(IMPORTS_BATCH.TOTAL_ROWS, batch.totalRows())
                .set(IMPORTS_BATCH.VALID_ROWS, batch.validRows())
                .set(IMPORTS_BATCH.REJECTED_ROWS, batch.rejectedRows())
                .set(IMPORTS_BATCH.CREATED_ROWS, batch.createdRows())
                .set(IMPORTS_BATCH.SKIPPED_ROWS, batch.skippedRows())
                .set(IMPORTS_BATCH.SIMULATION_ID, batch.simulationId())
                .set(IMPORTS_BATCH.CREATED_BY, batch.createdBy())
                .set(IMPORTS_BATCH.CREATED_AT, toDatabase(batch.createdAt()))
                .set(IMPORTS_BATCH.APPLIED_AT, batch.appliedAt() == null ? null : toDatabase(batch.appliedAt()))
                .execute();
    }

    @Override
    public Optional<ImportBatch> findValidated(InstitutionId institutionId, UUID createdBy, ImportKind kind,
            String fileSha256) {
        return dsl.selectFrom(IMPORTS_BATCH)
                .where(IMPORTS_BATCH.INSTITUTION_ID.eq(institutionId.value()))
                .and(IMPORTS_BATCH.CREATED_BY.eq(createdBy))
                .and(IMPORTS_BATCH.KIND.eq(kind.name()))
                .and(IMPORTS_BATCH.STATUS.eq(ImportStatus.VALIDATED.name()))
                .and(IMPORTS_BATCH.FILE_SHA256.eq(fileSha256))
                .orderBy(IMPORTS_BATCH.CREATED_AT.desc(), IMPORTS_BATCH.ID.desc())
                .limit(1)
                .fetchOptional(JooqImportBatchRepository::toDomain);
    }

    @Override
    public Optional<ImportBatch> latestOf(InstitutionId institutionId, UUID createdBy, ImportKind kind) {
        return dsl.selectFrom(IMPORTS_BATCH)
                .where(IMPORTS_BATCH.INSTITUTION_ID.eq(institutionId.value()))
                .and(IMPORTS_BATCH.CREATED_BY.eq(createdBy))
                .and(IMPORTS_BATCH.KIND.eq(kind.name()))
                .orderBy(IMPORTS_BATCH.CREATED_AT.desc(), IMPORTS_BATCH.ID.desc())
                .limit(1)
                .fetchOptional(JooqImportBatchRepository::toDomain);
    }

    @Override
    public Page<ImportBatch> list(InstitutionId institutionId, PageRequest request, Set<ImportKind> kinds) {
        Condition scope = IMPORTS_BATCH.INSTITUTION_ID.eq(institutionId.value())
                .and(IMPORTS_BATCH.KIND.in(kinds.stream().map(Enum::name).toList()));
        long total = dsl.fetchCount(IMPORTS_BATCH, scope);
        List<ImportBatch> rows = dsl.selectFrom(IMPORTS_BATCH)
                .where(scope)
                .orderBy(IMPORTS_BATCH.CREATED_AT.desc(), IMPORTS_BATCH.ID.desc())
                .limit(request.size()).offset(request.offset())
                .fetch(JooqImportBatchRepository::toDomain);
        return new Page<>(rows, total, request.page(), request.size());
    }
    @Override
    public Map<ImportKind, AppliedTotals> appliedTotals(InstitutionId institutionId) {
        Map<ImportKind, int[]> totals = new EnumMap<>(ImportKind.class);
        dsl.selectFrom(IMPORTS_BATCH)
                .where(IMPORTS_BATCH.INSTITUTION_ID.eq(institutionId.value()))
                .and(IMPORTS_BATCH.STATUS.eq(ImportStatus.APPLIED.name()))
                .orderBy(IMPORTS_BATCH.CREATED_AT.desc(), IMPORTS_BATCH.ID.desc())
                .fetch().forEach(row -> {
                    ImportKind kind = ImportKind.valueOf(row.getKind());
                    // The rows come newest first: the first of a kind is the one whose rejections count.
                    int[] total = totals.computeIfAbsent(kind, k -> new int[] {0, row.getRejectedRows()});
                    total[0] = Math.addExact(total[0], row.getCreatedRows());
                });
        Map<ImportKind, AppliedTotals> result = new EnumMap<>(ImportKind.class);
        totals.forEach((kind, total) -> result.put(kind, new AppliedTotals(total[0], total[1])));
        return result;
    }


    private static ImportBatch toDomain(ImportsBatchRecord row) {
        OffsetDateTime applied = row.getAppliedAt();
        return new ImportBatch(row.getId(), ImportKind.valueOf(row.getKind()), ImportStatus.valueOf(row.getStatus()),
                row.getFileSha256(), row.getTotalRows(), row.getValidRows(), row.getRejectedRows(),
                row.getCreatedRows(), row.getSkippedRows(), row.getSimulationId(), row.getCreatedBy(),
                row.getCreatedAt().toInstant(), applied == null ? null : applied.toInstant());
    }

    private static OffsetDateTime toDatabase(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
