package com.confia.shared.audit;

import com.confia.kernel.InstitutionId;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The walk of design.md decision 11: {@code running} starts at 32 zero bytes and advances with each
 * row's <em>stored</em> {@code row_hash}, never the recalculated one — irrelevant once the walk
 * stops at the first divergence, but exactly what lets a future incremental verifier (cambio 9)
 * resume from any row using its stored hash. Stops immediately at the first divergence
 * (specs/audit-trail/spec.md: "sin continuar reportando una segunda divergencia derivada de la
 * primera").
 *
 * <p><b>Aislamiento.</b> {@link #verifyChainOf} opens its own transaction through the real {@link
 * TransactionRunner}, fixing {@code app.institution_id} as the transaction's first statement, so
 * {@link AuditLogReader#pageOf} only ever sees {@code institutionId}'s own rows — enforced by the
 * same row-level-security policy production traffic uses, no {@code BYPASSRLS} anywhere (design.md
 * decision 11, "Aislamiento").
 */
public final class DefaultAuditChainVerifier implements AuditChainVerifier {

    private static final byte[] GENESIS_PREV_HASH = new byte[32];
    private static final int PAGE_SIZE = 1_000;

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .build();

    private final TransactionRunner transactionRunner;
    private final AuditLogReader reader;
    private final CanonicalAuditRowSerializer serializer;

    public DefaultAuditChainVerifier(TransactionRunner transactionRunner, AuditLogReader reader,
            CanonicalAuditRowSerializer serializer) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    @Override
    public AuditChainVerification verifyChainOf(InstitutionId institutionId) {
        Objects.requireNonNull(institutionId, "institutionId");
        SecurityContext context = new SecurityContext("", "system",
                institutionId.value().toString(), UUID.randomUUID().toString());
        return transactionRunner.execute(context, () -> walk(institutionId));
    }

    /**
     * Package-private, not {@code private}: {@link DefaultAuditChainVerifierTest} calls this
     * directly, against a fake {@link AuditLogReader}, to exercise the walk's branch logic
     * (genesis-missing, genesis {@code prev_hash} mismatch, non-genesis {@code prev_hash} mismatch,
     * {@code row_hash} mismatch, empty and intact) without needing the real {@link
     * TransactionRunner}'s database connection — the same reasoning {@code
     * CommittingPostgresIntegrationTest#tablesWithoutABeforeTruncateTrigger} already documents for
     * package-visible test access to a real implementation instead of a duplicated one.
     */
    AuditChainVerification walk(InstitutionId institutionId) {
        byte[] running = GENESIS_PREV_HASH;
        long verifiedRows = 0;
        long afterId = 0;
        while (true) {
            List<AuditRowSnapshot> page = reader.pageOf(institutionId, afterId, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (AuditRowSnapshot row : page) {
                AuditChainVerification.Diverged divergence =
                        checkPrevHash(institutionId, row, running, verifiedRows);
                if (divergence != null) {
                    return divergence;
                }
                byte[] expectedHash = serializer.rowHash(toCanonicalRow(running, row));
                if (!Arrays.equals(expectedHash, row.rowHash())) {
                    return new AuditChainVerification.Diverged(institutionId, verifiedRows, row.id(),
                            AuditChainVerification.Divergence.ROW_HASH_MISMATCH, expectedHash,
                            row.rowHash());
                }
                running = row.rowHash();
                verifiedRows++;
                afterId = row.id();
            }
        }
        if (verifiedRows == 0) {
            return new AuditChainVerification.Empty(institutionId);
        }
        return new AuditChainVerification.Intact(institutionId, verifiedRows, running);
    }

    /** {@code null} when {@code row.prevHash()} is consistent with the chain so far. */
    private static AuditChainVerification.Diverged checkPrevHash(InstitutionId institutionId,
            AuditRowSnapshot row, byte[] running, long verifiedRows) {
        if (verifiedRows == 0) {
            if (row.id() != 1) {
                return new AuditChainVerification.Diverged(institutionId, verifiedRows, row.id(),
                        AuditChainVerification.Divergence.MISSING_GENESIS, GENESIS_PREV_HASH,
                        row.prevHash());
            }
            if (!Arrays.equals(row.prevHash(), GENESIS_PREV_HASH)) {
                return new AuditChainVerification.Diverged(institutionId, verifiedRows, row.id(),
                        AuditChainVerification.Divergence.GENESIS_PREV_HASH_MISMATCH,
                        GENESIS_PREV_HASH, row.prevHash());
            }
            return null;
        }
        if (!Arrays.equals(row.prevHash(), running)) {
            return new AuditChainVerification.Diverged(institutionId, verifiedRows, row.id(),
                    AuditChainVerification.Divergence.PREV_HASH_MISMATCH, running, row.prevHash());
        }
        return null;
    }

    private static CanonicalAuditRow toCanonicalRow(byte[] prevHash, AuditRowSnapshot row) {
        return new CanonicalAuditRow(prevHash, row.id(), row.institutionId(), row.occurredAt(),
                row.actorId(), row.actorKind(), row.actorLabel(), row.sourceIp(), row.userAgent(),
                row.requestId(), row.traceId(), row.action(), row.entityType(), row.entityId(),
                row.outcome(), parseJson(row.beforeValue()), parseJson(row.afterValue()),
                row.reason(), row.approverId());
    }

    /** {@code null} for a SQL-{@code NULL} {@code jsonb} column, never for the JSON literal null. */
    private static JsonNode parseJson(String rawJsonbText) {
        return rawJsonbText == null ? null : JSON_MAPPER.readTree(rawJsonbText);
    }
}
