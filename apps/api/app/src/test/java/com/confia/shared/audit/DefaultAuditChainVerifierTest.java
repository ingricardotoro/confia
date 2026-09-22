package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import com.confia.kernel.InstitutionId;
import com.confia.shared.security.TransactionRunner;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit-level branch coverage of {@link DefaultAuditChainVerifier#walk} (design.md decision 12,
 * the 95% line/branch {@code PACKAGE} gate for {@code com.confia.shared.audit}), against a fake
 * {@link AuditLogReader} — no Docker, no real transaction: {@code walk} needs only already-fetched
 * rows, never the database itself. Complements {@link AuditChainVerifierIT} and {@link
 * AuditChainKnownLimitIT}, which already prove the real, end-to-end path (real trigger, real
 * {@code SUPERUSER} manipulation, real row-level security) but, by construction, never manipulate
 * {@code prev_hash} directly and never leave an institution with zero rows — exactly the branches
 * this class exists to cover honestly, not to work around the coverage gate.
 */
class DefaultAuditChainVerifierTest {

    private static final CanonicalAuditRowSerializer SERIALIZER = new CanonicalAuditRowSerializer();
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final byte[] GENESIS_PREV_HASH = new byte[32];
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();

    @Test
    void emptyInstitutionReportsEmpty() {
        InstitutionId institutionId = randomInstitutionId();

        AuditChainVerification result = verifierOver(List.of()).walk(institutionId);

        assertThat(result).asInstanceOf(type(AuditChainVerification.Empty.class))
                .satisfies(empty -> assertThat(empty.institutionId()).isEqualTo(institutionId));
    }

    @Test
    void firstFetchedRowNotIdOneIsMissingGenesis() {
        InstitutionId institutionId = randomInstitutionId();
        AuditRowSnapshot row = validRow(2, institutionId.value(), GENESIS_PREV_HASH, null, null);

        AuditChainVerification result = verifierOver(List.of(row)).walk(institutionId);

        assertThat(result).asInstanceOf(type(AuditChainVerification.Diverged.class)).satisfies(diverged -> {
            assertThat(diverged.divergence()).isEqualTo(AuditChainVerification.Divergence.MISSING_GENESIS);
            assertThat(diverged.firstDivergentId()).isEqualTo(2L);
        });
    }

    @Test
    void genesisRowWithNonZeroPrevHashIsGenesisPrevHashMismatch() {
        InstitutionId institutionId = randomInstitutionId();
        AuditRowSnapshot row = validRow(1, institutionId.value(), randomBytes(32), null, null);

        AuditChainVerification result = verifierOver(List.of(row)).walk(institutionId);

        assertThat(result).asInstanceOf(type(AuditChainVerification.Diverged.class)).satisfies(diverged ->
                assertThat(diverged.divergence())
                        .isEqualTo(AuditChainVerification.Divergence.GENESIS_PREV_HASH_MISMATCH));
    }

    @Test
    void secondRowWithWrongPrevHashIsPrevHashMismatch() {
        InstitutionId institutionId = randomInstitutionId();
        AuditRowSnapshot first = validRow(1, institutionId.value(), GENESIS_PREV_HASH, null, null);
        AuditRowSnapshot second = validRow(2, institutionId.value(), randomBytes(32), null, null);

        AuditChainVerification result = verifierOver(List.of(first, second)).walk(institutionId);

        assertThat(result).asInstanceOf(type(AuditChainVerification.Diverged.class)).satisfies(diverged -> {
            assertThat(diverged.divergence()).isEqualTo(AuditChainVerification.Divergence.PREV_HASH_MISMATCH);
            assertThat(diverged.firstDivergentId()).isEqualTo(2L);
        });
    }

    @Test
    void rowWithATamperedStoredHashIsRowHashMismatch() {
        InstitutionId institutionId = randomInstitutionId();
        AuditRowSnapshot valid = validRow(1, institutionId.value(), GENESIS_PREV_HASH, null, null);
        AuditRowSnapshot tampered = withRowHash(valid, randomBytes(32));

        AuditChainVerification result = verifierOver(List.of(tampered)).walk(institutionId);

        assertThat(result).asInstanceOf(type(AuditChainVerification.Diverged.class)).satisfies(diverged ->
                assertThat(diverged.divergence())
                        .isEqualTo(AuditChainVerification.Divergence.ROW_HASH_MISMATCH));
    }

    @Test
    void twoValidRowsWithJsonPayloadsReportIntact() {
        InstitutionId institutionId = randomInstitutionId();
        AuditRowSnapshot first = validRow(1, institutionId.value(), GENESIS_PREV_HASH,
                "{\"before\":1}", "null");
        AuditRowSnapshot second = validRow(2, institutionId.value(), first.rowHash(), null, null);

        AuditChainVerification result = verifierOver(List.of(first, second)).walk(institutionId);

        assertThat(result).asInstanceOf(type(AuditChainVerification.Intact.class)).satisfies(intact -> {
            assertThat(intact.institutionId()).isEqualTo(institutionId);
            assertThat(intact.verifiedRows()).isEqualTo(2L);
            assertThat(intact.lastRowHash()).isEqualTo(second.rowHash());
        });
    }

    /**
     * The {@link TransactionRunner} and {@link DataSource} here are never invoked: {@code walk}
     * only ever touches {@code reader} and {@code serializer}. They exist only because {@link
     * DefaultAuditChainVerifier}'s constructor validates every argument non-null, exactly as a
     * production constructor should.
     */
    private static DefaultAuditChainVerifier verifierOver(List<AuditRowSnapshot> rows) {
        TransactionRunner unusedTransactionRunner =
                new TransactionRunner(UNUSED_TRANSACTION_MANAGER, UNUSED_DATA_SOURCE);
        return new DefaultAuditChainVerifier(unusedTransactionRunner, new FixedRowsAuditLogReader(rows),
                SERIALIZER);
    }

    private static final PlatformTransactionManager UNUSED_TRANSACTION_MANAGER =
            new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    throw new UnsupportedOperationException("walk() never opens a transaction");
                }

                @Override
                public void commit(TransactionStatus status) {
                    throw new UnsupportedOperationException("walk() never opens a transaction");
                }

                @Override
                public void rollback(TransactionStatus status) {
                    throw new UnsupportedOperationException("walk() never opens a transaction");
                }
            };

    private static final DataSource UNUSED_DATA_SOURCE = new DriverManagerDataSource();

    private static AuditRowSnapshot validRow(long id, UUID institutionId, byte[] prevHash,
            String beforeValueJson, String afterValueJson) {
        CanonicalAuditRow canonicalRow = new CanonicalAuditRow(prevHash, id, institutionId, OCCURRED_AT,
                ACTOR_ID, "system", "unit-test-actor", null, null, REQUEST_ID, null, "test.action",
                "test_entity", "entity-1", "success", jsonNodeOf(beforeValueJson),
                jsonNodeOf(afterValueJson), null, null);
        byte[] rowHash = SERIALIZER.rowHash(canonicalRow);
        return new AuditRowSnapshot(id, institutionId, OCCURRED_AT, ACTOR_ID, "system",
                "unit-test-actor", null, null, REQUEST_ID, null, "test.action", "test_entity",
                "entity-1", "success", beforeValueJson, afterValueJson, null, null, prevHash, rowHash);
    }

    private static JsonNode jsonNodeOf(String rawJson) {
        return rawJson == null ? null : JSON_MAPPER.readTree(rawJson);
    }

    private static AuditRowSnapshot withRowHash(AuditRowSnapshot row, byte[] rowHash) {
        return new AuditRowSnapshot(row.id(), row.institutionId(), row.occurredAt(), row.actorId(),
                row.actorKind(), row.actorLabel(), row.sourceIp(), row.userAgent(), row.requestId(),
                row.traceId(), row.action(), row.entityType(), row.entityId(), row.outcome(),
                row.beforeValue(), row.afterValue(), row.reason(), row.approverId(), row.prevHash(),
                rowHash);
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static InstitutionId randomInstitutionId() {
        return new InstitutionId(UUID.randomUUID());
    }

    /** In-memory {@link AuditLogReader}, sorted and paged exactly like the real jOOQ adapter. */
    private record FixedRowsAuditLogReader(List<AuditRowSnapshot> rows) implements AuditLogReader {
        @Override
        public List<AuditRowSnapshot> pageOf(InstitutionId institutionId, long afterId, int pageSize) {
            return rows.stream()
                    .filter(row -> row.institutionId().equals(institutionId.value()) && row.id() > afterId)
                    .sorted(Comparator.comparingLong(AuditRowSnapshot::id))
                    .limit(pageSize)
                    .toList();
        }
    }
}
