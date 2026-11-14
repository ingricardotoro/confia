package com.confia.students.application;

import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Recomputes, after a rotation of the blind-index key, the stored blind indexes that carry another version
 * than the current one (docs/03-seguridad.md section 7.3). Each batch is its own transaction with the
 * {@code system} actor of the institution; it locks the rows it recomputes, decrypts each document through
 * the port, replaces only the index and writes one audit entry with the counts (never a document, a
 * ciphertext or an index). It is idempotent: a row already on the current version is not selected, so a
 * rerun, or a run interrupted halfway, simply continues.
 *
 * <p>It is not a job of the worker: the worker holds no column-encryption key by design (ADR-0023), and
 * decrypting is the only way to recompute an index. The administrative process runs it once, on demand,
 * for the institutions named in {@code confia.students.reindex.institutions}.
 */
public final class ReindexNationalIds {

    public static final String ACTION = "students.national_id.reindexed";
    public static final int BATCH_SIZE = 200;
    public static final int MAX_BATCHES = 10_000;

    private static final String ACTOR_LABEL = "blind-index-reindex";
    private static final String ENTITY_TYPE = "students.student";

    private final TransactionRunner transactionRunner;
    private final StudentRepository students;
    private final NationalIdReindexer reindexer;
    private final AuditLogWriter auditLogWriter;

    public ReindexNationalIds(TransactionRunner transactionRunner, StudentRepository students,
            NationalIdReindexer reindexer, AuditLogWriter auditLogWriter) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.students = Objects.requireNonNull(students, "students");
        this.reindexer = Objects.requireNonNull(reindexer, "reindexer");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
    }

    /** Recomputes every pending index of the institution; returns how many it recomputed. */
    public long reindex(InstitutionId institution, UUID runId) {
        Objects.requireNonNull(institution, "institution");
        Objects.requireNonNull(runId, "runId");
        String version = reindexer.currentVersion();
        SecurityContext context = new SecurityContext("", "system", institution.value().toString(), runId.toString());
        long total = 0;
        for (int batch = 1; batch <= MAX_BATCHES; batch++) {
            int number = batch;
            int done = transactionRunner.execute(context, () -> reindexBatch(institution, runId, version, number));
            total += done;
            if (done < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    private int reindexBatch(InstitutionId institution, UUID runId, String version, int number) {
        List<IndexedNationalId> pending = students.lockNationalIdsNotIndexedWith(institution, version, BATCH_SIZE);
        for (IndexedNationalId row : pending) {
            students.replaceBlindIndex(institution, row.studentId(),
                    reindexer.recompute(institution, row.studentId(), row.encrypted()));
        }
        if (!pending.isEmpty()) {
            auditLogWriter.append(new AuditEntry(institution.value(), null, "system", ACTOR_LABEL, null, null,
                    runId, null, ACTION, ENTITY_TYPE, runId.toString(), "success", null,
                    AuditJson.object().text("version", version).number("recomputed", pending.size())
                            .number("batch", number).toJson(), null, null));
        }
        return pending.size();
    }
}
