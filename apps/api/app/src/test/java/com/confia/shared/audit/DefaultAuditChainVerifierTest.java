package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import com.confia.kernel.InstitutionId;
import com.confia.shared.security.TransactionRunner;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    // ---- verifySince (design.md decision 19; scenarios AT07 to AT09, AT25 and AT26) ----

    private static final Instant SINCE = Instant.parse("2026-09-01T06:00:00Z");
    private static final Instant BEFORE = Instant.parse("2026-08-31T23:00:00Z");
    private static final Instant INSIDE = Instant.parse("2026-09-10T15:00:00Z");

    /** A chain of rows 1, 2, ... linked by their stored hashes, one at each of {@code occurredAt}. */
    private static List<AuditRowSnapshot> chainAt(InstitutionId institutionId, Instant... occurredAt) {
        List<AuditRowSnapshot> rows = new ArrayList<>();
        byte[] prev = GENESIS_PREV_HASH;
        for (int i = 0; i < occurredAt.length; i++) {
            AuditRowSnapshot row = validRow(i + 1, institutionId.value(), prev, occurredAt[i]);
            rows.add(row);
            prev = row.rowHash();
        }
        return rows;
    }

    /** Scenario AT07: only the rows of the window are verified, and they are reported intact. */
    @Test
    void anIntactWindowReportsIntegrityAndHowManyRowsItVerified() {
        InstitutionId institutionId = randomInstitutionId();
        List<AuditRowSnapshot> rows = chainAt(institutionId, BEFORE, BEFORE, INSIDE, INSIDE, INSIDE);

        AuditChainVerification result = verifierOver(rows).walkSince(institutionId, SINCE);

        assertThat(result).asInstanceOf(type(AuditChainVerification.Intact.class)).satisfies(intact -> {
            assertThat(intact.institutionId()).isEqualTo(institutionId);
            assertThat(intact.verifiedRows()).isEqualTo(3L);
            assertThat(intact.lastRowHash()).isEqualTo(rows.get(4).rowHash());
        });
    }

    /** The window includes the row at exactly {@code since} and leaves out the one a microsecond before. */
    @Test
    void theRowAtExactlyTheStartIsInsideAndOneMicrosecondBeforeItIsOutside() {
        InstitutionId institutionId = randomInstitutionId();
        List<AuditRowSnapshot> rows = chainAt(institutionId, SINCE.minusNanos(1_000), SINCE, INSIDE);

        AuditChainVerification result = verifierOver(rows).walkSince(institutionId, SINCE);

        assertThat(result).asInstanceOf(type(AuditChainVerification.Intact.class))
                .satisfies(intact -> assertThat(intact.verifiedRows()).isEqualTo(2L));
    }

    /** Scenario AT08: the first divergent row of the window is named by its institution and id. */
    @Test
    void aRowAlteredInsideTheWindowIsIdentifiedExactly() {
        InstitutionId institutionId = randomInstitutionId();
        List<AuditRowSnapshot> rows = new ArrayList<>(
                chainAt(institutionId, BEFORE, INSIDE, INSIDE, INSIDE, INSIDE));
        rows.set(3, withRowHash(rows.get(3), randomBytes(32)));

        AuditChainVerification result = verifierOver(rows).walkSince(institutionId, SINCE);

        assertThat(result).asInstanceOf(type(AuditChainVerification.Diverged.class)).satisfies(diverged -> {
            assertThat(diverged.institutionId()).isEqualTo(institutionId);
            assertThat(diverged.firstDivergentId()).isEqualTo(4L);
            assertThat(diverged.verifiedRows()).as("rows 2 and 3 were verified before it").isEqualTo(2L);
            assertThat(diverged.divergence()).isEqualTo(AuditChainVerification.Divergence.ROW_HASH_MISMATCH);
        });
    }

    /** Scenario AT09: an alteration before the window is invisible to the window and seen by the full walk. */
    @Test
    void anAlterationBeforeTheWindowIsNotSeenByTheWindowButIsByTheWholeChain() {
        InstitutionId institutionId = randomInstitutionId();
        List<AuditRowSnapshot> rows = new ArrayList<>(chainAt(institutionId, BEFORE, BEFORE, INSIDE));
        rows.set(0, withRowHash(rows.get(0), randomBytes(32)));
        DefaultAuditChainVerifier verifier = verifierOver(rows);

        assertThat(verifier.walkSince(institutionId, SINCE)).isInstanceOf(AuditChainVerification.Intact.class);
        assertThat(verifier.walk(institutionId)).asInstanceOf(type(AuditChainVerification.Diverged.class))
                .satisfies(diverged -> assertThat(diverged.firstDivergentId()).isEqualTo(1L));
    }

    /** Scenario AT25: the link at the border is not checked; the walk starts from the stored previous hash. */
    @Test
    void theLinkAtTheBorderIsNotVerifiedAndTheWalkStartsFromTheStoredPreviousHash() {
        InstitutionId institutionId = randomInstitutionId();
        AuditRowSnapshot before = validRow(1, institutionId.value(), GENESIS_PREV_HASH, BEFORE);
        AuditRowSnapshot first = validRow(2, institutionId.value(), randomBytes(32), INSIDE);
        AuditRowSnapshot second = validRow(3, institutionId.value(), first.rowHash(), INSIDE);
        DefaultAuditChainVerifier verifier = verifierOver(List.of(before, first, second));

        assertThat(verifier.walkSince(institutionId, SINCE)).asInstanceOf(type(AuditChainVerification.Intact.class))
                .satisfies(intact -> assertThat(intact.verifiedRows()).isEqualTo(2L));
        assertThat(verifier.walk(institutionId)).asInstanceOf(type(AuditChainVerification.Diverged.class))
                .satisfies(diverged -> {
                    assertThat(diverged.firstDivergentId()).isEqualTo(2L);
                    assertThat(diverged.divergence())
                            .isEqualTo(AuditChainVerification.Divergence.PREV_HASH_MISMATCH);
                });
    }

    /**
     * Review R3-002: the link of the first row is skipped, its content is not. Altering the content of the first row
     * of the window (its stored hashes untouched) is a row hash mismatch at that very row, with nothing verified yet.
     */
    @Test
    void theContentOfTheFirstRowOfTheWindowIsStillRecalculatedAndATamperIsDetected() {
        InstitutionId institutionId = randomInstitutionId();
        List<AuditRowSnapshot> rows = new ArrayList<>(chainAt(institutionId, BEFORE, INSIDE, INSIDE));
        rows.set(1, withReason(rows.get(1), "tampered"));

        AuditChainVerification result = verifierOver(rows).walkSince(institutionId, SINCE);

        assertThat(result).asInstanceOf(type(AuditChainVerification.Diverged.class)).satisfies(diverged -> {
            assertThat(diverged.firstDivergentId()).isEqualTo(2L);
            assertThat(diverged.verifiedRows()).isZero();
            assertThat(diverged.divergence()).isEqualTo(AuditChainVerification.Divergence.ROW_HASH_MISMATCH);
            assertThat(diverged.storedHash()).isEqualTo(rows.get(1).rowHash());
        });
    }

    /**
     * Review R3-001, the documented behaviour: after the first row at or after {@code since}, the walk is by id with
     * no further filter on {@code occurred_at}, so a later row older than {@code since} is recalculated and counted.
     */
    @Test
    void whenOccurredAtIsNotMonotonicTheWindowIsTheIdWalkFromItsFirstRow() {
        InstitutionId institutionId = randomInstitutionId();
        List<AuditRowSnapshot> rows = new ArrayList<>(chainAt(institutionId, BEFORE, INSIDE, BEFORE, INSIDE));

        assertThat(verifierOver(rows).walkSince(institutionId, SINCE))
                .asInstanceOf(type(AuditChainVerification.Intact.class))
                .satisfies(intact -> {
                    assertThat(intact.verifiedRows()).as("ids 2, 3 and 4, although id 3 is older than since")
                            .isEqualTo(3L);
                    assertThat(intact.lastRowHash()).isEqualTo(rows.get(3).rowHash());
                });

        rows.set(2, withRowHash(rows.get(2), randomBytes(32)));
        assertThat(verifierOver(rows).walkSince(institutionId, SINCE))
                .asInstanceOf(type(AuditChainVerification.Diverged.class))
                .satisfies(diverged -> assertThat(diverged.firstDivergentId())
                        .as("the older row in the middle of the id walk is not skipped").isEqualTo(3L));
    }

    /** A window that starts after the genesis row never reports a missing genesis. */
    @Test
    void aWindowThatStartsAfterTheGenesisRowIsNotAMissingGenesis() {
        InstitutionId institutionId = randomInstitutionId();
        List<AuditRowSnapshot> rows = chainAt(institutionId, BEFORE, INSIDE);

        assertThat(verifierOver(rows).walkSince(institutionId, SINCE))
                .asInstanceOf(type(AuditChainVerification.Intact.class))
                .satisfies(intact -> assertThat(intact.verifiedRows()).isEqualTo(1L));
    }

    /** Scenario AT26: a window without rows is empty, while the whole chain is intact. */
    @Test
    void aWindowWithoutRowsIsEmptyAndTheWholeChainIsIntact() {
        InstitutionId institutionId = randomInstitutionId();
        List<AuditRowSnapshot> rows = chainAt(institutionId, BEFORE, BEFORE);
        DefaultAuditChainVerifier verifier = verifierOver(rows);

        assertThat(verifier.walkSince(institutionId, SINCE)).asInstanceOf(type(AuditChainVerification.Empty.class))
                .satisfies(empty -> assertThat(empty.institutionId()).isEqualTo(institutionId));
        assertThat(verifier.walk(institutionId)).isInstanceOf(AuditChainVerification.Intact.class);
    }

    /** Another institution's rows are never part of the window. */
    @Test
    void theWindowOfOneInstitutionNeverIncludesTheRowsOfAnother() {
        InstitutionId institutionId = randomInstitutionId();
        InstitutionId other = randomInstitutionId();
        List<AuditRowSnapshot> rows = new ArrayList<>(chainAt(institutionId, BEFORE, INSIDE));
        rows.addAll(chainAt(other, INSIDE, INSIDE, INSIDE));

        assertThat(verifierOver(rows).walkSince(institutionId, SINCE))
                .asInstanceOf(type(AuditChainVerification.Intact.class))
                .satisfies(intact -> assertThat(intact.verifiedRows()).isEqualTo(1L));
    }

    @Test
    void verifySinceRequiresItsArguments() {
        DefaultAuditChainVerifier verifier = verifierOver(List.of());

        assertThatThrownBy(() -> verifier.verifySince(null, SINCE)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("institutionId");
        assertThatThrownBy(() -> verifier.verifySince(randomInstitutionId(), null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("since");
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

    private static AuditRowSnapshot validRow(long id, UUID institutionId, byte[] prevHash, Instant occurredAt) {
        return validRow(id, institutionId, prevHash, occurredAt, null, null);
    }

    private static AuditRowSnapshot validRow(long id, UUID institutionId, byte[] prevHash,
            String beforeValueJson, String afterValueJson) {
        return validRow(id, institutionId, prevHash, OCCURRED_AT, beforeValueJson, afterValueJson);
    }

    private static AuditRowSnapshot validRow(long id, UUID institutionId, byte[] prevHash, Instant occurredAt,
            String beforeValueJson, String afterValueJson) {
        CanonicalAuditRow canonicalRow = new CanonicalAuditRow(prevHash, id, institutionId, occurredAt,
                ACTOR_ID, "system", "unit-test-actor", null, null, REQUEST_ID, null, "test.action",
                "test_entity", "entity-1", "success", jsonNodeOf(beforeValueJson),
                jsonNodeOf(afterValueJson), null, null);
        byte[] rowHash = SERIALIZER.rowHash(canonicalRow);
        return new AuditRowSnapshot(id, institutionId, occurredAt, ACTOR_ID, "system",
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

    private static AuditRowSnapshot withReason(AuditRowSnapshot row, String reason) {
        return new AuditRowSnapshot(row.id(), row.institutionId(), row.occurredAt(), row.actorId(),
                row.actorKind(), row.actorLabel(), row.sourceIp(), row.userAgent(), row.requestId(),
                row.traceId(), row.action(), row.entityType(), row.entityId(), row.outcome(),
                row.beforeValue(), row.afterValue(), reason, row.approverId(), row.prevHash(),
                row.rowHash());
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

        @Override
        public Optional<AuditRowSnapshot> firstAtOrAfter(InstitutionId institutionId, Instant occurredAt) {
            return rows.stream()
                    .filter(row -> row.institutionId().equals(institutionId.value())
                            && !row.occurredAt().isBefore(occurredAt))
                    .min(Comparator.comparingLong(AuditRowSnapshot::id));
        }
    }
}
