package com.confia.imports.application;

import com.confia.imports.domain.ImportBatch;
import com.confia.imports.domain.ImportKind;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.security.AuthenticatedActor;
import java.util.UUID;

/**
 * The entries of the import: {@code imports.batch.simulated}, {@code imports.batch.applied} and {@code imports.batch.failed}, with the state after
 * built with {@link AuditJson} and never by concatenation. The audit trail is kept seven years and never erased, so it
 * holds no personal data of the minors in the file: the kind, the status, the SHA-256 of the file and the counts. The
 * students an application creates leave their own entries, exactly those of a hand registration.
 */
final class ImportsAudit {

    static final String BATCH = "imports.batch";

    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_ERROR = "error";

    private ImportsAudit() {
    }

    /** The identifier of the request for the entry; a blank one gets a fresh identifier. */
    static UUID requestId(String requestId) {
        return requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
    }

    static AuditEntry simulated(AuthenticatedActor actor, UUID requestId, ImportBatch batch) {
        return entry(actor, requestId, BATCH + ".simulated", batch);
    }

    static AuditEntry applied(AuthenticatedActor actor, UUID requestId, ImportBatch batch) {
        return entry(actor, requestId, BATCH + ".applied", batch);
    }

    /**
     * An application that failed for an unexpected reason after some students were committed. It carries the kind,
     * the fingerprint and the counts only: never a value of the file and never the cause.
     */
    static AuditEntry failed(AuthenticatedActor actor, UUID requestId, UUID batchId, ImportKind kind, String fileSha256,
            int totalRows, int createdRows) {
        String after = AuditJson.object().text("kind", kind.name()).text("status", "FAILED")
                .text("fileSha256", fileSha256).number("totalRows", totalRows).number("createdRows", createdRows)
                .toJson();
        return new AuditEntry(actor.institutionId(), actor.accountId(), ACTOR_KIND_STAFF,
                actor.accountId().toString(), null, null, requestId, null, BATCH + ".failed", BATCH,
                batchId.toString(), OUTCOME_ERROR, null, after, null, null);
    }

    private static AuditEntry entry(AuthenticatedActor actor, UUID requestId, String action, ImportBatch batch) {
        return new AuditEntry(actor.institutionId(), actor.accountId(), ACTOR_KIND_STAFF,
                actor.accountId().toString(), null, null, requestId, null, action, BATCH, batch.id().toString(),
                OUTCOME_SUCCESS, null, json(batch), null, null);
    }

    static String json(ImportBatch batch) {
        return AuditJson.object().text("kind", batch.kind().name()).text("status", batch.status().name())
                .text("fileSha256", batch.fileSha256()).number("totalRows", batch.totalRows())
                .number("validRows", batch.validRows()).number("rejectedRows", batch.rejectedRows())
                .number("createdRows", batch.createdRows()).number("skippedRows", batch.skippedRows()).toJson();
    }
}
