package com.confia.shared.security;

import static confia.generated.jooq.tables.OrganizationInstitution.ORGANIZATION_INSTITUTION;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqIdempotencyRecordStore;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F0's exit criterion 4: two concurrent requests with the same idempotency key produce exactly one
 * accounting effect (design.md, decision 10; specs/build-integrity/spec.md, requirement "Criterio
 * de salida 4 de F0..."). PR C3.
 *
 * <p><b>The substrate is a cumulative update over {@code legal_name}, a {@code NOT NULL} column of
 * {@code organization_institution}, and not either alternative apparently equivalent to it, for two
 * reasons written here rather than left implicit (design.md, decision 10):</b>
 *
 * <ul>
 *   <li><b>Not {@code trade_name}.</b> That column allows {@code NULL}, and in SQL {@code NULL ||
 *       '+'} is {@code NULL} — an effect that silently swallows itself, and a test that would pass
 *       with no effect at all. {@code legal_name} is {@code NOT NULL} (V1 migration), which removes
 *       that trap by construction.
 *   <li><b>Not a row count on {@code shared_audit_log}.</b> That table's chaining trigger takes a
 *       per-institution lock on {@code shared_audit_chain_head} (V3, change 5) that would
 *       <em>serialize the two transactions before either one ever reaches the idempotency
 *       marker</em>, so the test could pass because the ledger serializes, not because idempotency
 *       does anything — a green for the wrong reason, the exact family this repository has already
 *       paid to learn about three times.
 * </ul>
 *
 * <p>Two independent {@link IdempotentExecutor} instances, each with its own {@link
 * TransactionRunner} and its own connection, synchronized with a {@link CyclicBarrier} exactly like
 * {@link IdempotentExecutorConcurrencyIT}'s own scenario (b): thread A writes its marker first and
 * reaches the barrier from inside its own use case (proving the {@code INSERT} already ran), and
 * holds its transaction open for a short, deterministic window before committing so thread B's own
 * {@code INSERT} genuinely collides with A's still-uncommitted row. Both threads use the identical
 * production use case — the real cumulative {@code UPDATE}, through the real {@link
 * IdempotentExecutor} and the real {@link TransactionRunner} against real PostgreSQL — never a
 * fixed-value update, which would be idempotent by nature and would testify nothing
 * (specs/build-integrity/spec.md, this requirement's own wording).
 *
 * <p><b>What is real production demonstration here, and what is partial</b> (design.md, decision
 * 10; proposal.md, "El sustrato de demostración..."): the production table, its forced row-level
 * security policy, the security context {@link TransactionRunner} fixes for real, the real
 * privileges of {@code confia_admin_app}, and transactions that genuinely commit are all real. What
 * this test supplies that production code does not yet have is the use case itself: {@code
 * JooqInstitutionRepository} only exposes {@code findById}, and no Java write path exists yet over
 * {@code organization_institution} outside this test.
 *
 * <p><b>The test asserts two things, never one</b> (design.md, decision 10): the accumulated effect
 * happened exactly once, <em>and</em> the losing request's own outcome is one of the three declared
 * kinds — {@link IdempotentOutcome.Replayed}, {@link IdempotencyConflictException}, or {@link
 * IdempotentOutcome.Executed} — never an unexplained error. Without the second assertion, a failure
 * that kept the loser from ever reaching the effect would read as the control's success.
 */
class IdempotencyExitCriterionIT extends CommittingPostgresIntegrationTest {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final String ENDPOINT = "/api/v1/institutions/legal-name-append";
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Duration RETENTION = Duration.ofHours(24);

    private IdempotencyRecordStore store() {
        return new JooqIdempotencyRecordStore(dsl);
    }

    private IdempotentExecutor newExecutor(Duration lockWait) {
        return new IdempotentExecutor(newIndependentRunner(), store(), new RequestPayloadHasher(),
                FIXED_CLOCK, dataSource(), lockWait, RETENTION);
    }

    private TransactionRunner newIndependentRunner() {
        return new TransactionRunner(transactionManager(), dataSource());
    }

    @Test
    void twoConcurrentRequestsWithTheSameKeyApplyTheAccountingEffectExactlyOnce() throws Exception {
        InstitutionId institutionId = seedInstitution();
        IdempotencyKey key = new IdempotencyKey(ENDPOINT, UUID.randomUUID().toString());
        JsonNode payload = JSON_MAPPER.readTree("{\"op\":\"append-legal-name-suffix\"}");
        IdempotentResponse response = new IdempotentResponse(200, JSON_MAPPER.readTree("{}"));

        CyclicBarrier markerWrittenByA = new CyclicBarrier(2);
        IdempotentExecutor executorA = newExecutor(Duration.ofSeconds(5));
        IdempotentExecutor executorB = newExecutor(Duration.ofSeconds(5));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<IdempotentOutcome> futureA = pool.submit(() -> executorA.execute(
                    contextOf(institutionId), key, payload, () -> {
                        appendLegalNameSuffix(institutionId);
                        awaitUninterruptibly(markerWrittenByA);
                        sleepUninterruptibly(300); // well inside B's five-second lockWait
                        return response;
                    }));

            Future<IdempotentOutcome> futureB = pool.submit(() -> {
                awaitUninterruptibly(markerWrittenByA);
                return executorB.execute(contextOf(institutionId), key, payload, () -> {
                    appendLegalNameSuffix(institutionId);
                    return response;
                });
            });

            assertThat(futureA.get(15, TimeUnit.SECONDS))
                    .as("the winning request must complete without interference")
                    .isInstanceOf(IdempotentOutcome.Executed.class);

            assertOutcomeIsOneOfTheThreeDeclaredKinds(futureB);

            String legalName = legalNameOf(institutionId);
            assertThat(countPlusSuffixes(legalName))
                    .as("the cumulative effect on legal_name must have happened exactly once, "
                            + "never twice, regardless of which of the three outcomes resolved the "
                            + "losing request")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Design.md, decision 10, "la prueba afirma dos cosas, no una". The losing request must resolve
     * to one of {@link IdempotentOutcome.Replayed}, {@link IdempotencyConflictException} or {@link
     * IdempotentOutcome.Executed} — never an unexplained error, which is what would let a bug that
     * silently kept the loser from ever reaching the effect read as the control's own success.
     */
    private static void assertOutcomeIsOneOfTheThreeDeclaredKinds(Future<IdempotentOutcome> futureB)
            throws InterruptedException, TimeoutException {
        try {
            IdempotentOutcome outcomeB = futureB.get(15, TimeUnit.SECONDS);
            assertThat(outcomeB)
                    .as("the losing request's outcome must be Replayed or Executed when it does not "
                            + "throw at all")
                    .isInstanceOfAny(IdempotentOutcome.Replayed.class, IdempotentOutcome.Executed.class);
        } catch (ExecutionException e) {
            assertThat(e.getCause())
                    .as("the losing request's outcome must be one of the three declared kinds, "
                            + "never an unexplained error")
                    .isInstanceOf(IdempotencyConflictException.class);
        }
    }

    /**
     * The minimal production-shaped use case this demonstration needs (design.md, decision 10;
     * task 6.2): a real cumulative {@code UPDATE} against real PostgreSQL, through the {@code dsl}
     * this test class already shares with production code — the same {@code
     * TransactionAwareDataSourceProxy}-backed data source {@link IdempotentExecutor} and {@link
     * TransactionRunner} participate in, so this statement runs inside whichever transaction the
     * caller (T1 or T3) already opened, never a transaction of its own. No production code changes
     * for this: the mechanism has been complete since C2c (task 5.3); only the caller's use case is
     * new, and it lives in this test tree, not in {@code main}.
     */
    private void appendLegalNameSuffix(InstitutionId institutionId) {
        dsl.update(ORGANIZATION_INSTITUTION)
                .set(ORGANIZATION_INSTITUTION.LEGAL_NAME, ORGANIZATION_INSTITUTION.LEGAL_NAME.concat("+"))
                .where(ORGANIZATION_INSTITUTION.ID.eq(institutionId.value()))
                .execute();
    }

    private InstitutionId seedInstitution() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.insertInto(ORGANIZATION_INSTITUTION)
                    .set(ORGANIZATION_INSTITUTION.ID, institutionId.value())
                    .set(ORGANIZATION_INSTITUTION.LEGAL_NAME, "Instituto de prueba " + institutionId)
                    .set(ORGANIZATION_INSTITUTION.RTN, "12345678")
                    .set(ORGANIZATION_INSTITUTION.ADDRESS, "Dirección de prueba")
                    .set(ORGANIZATION_INSTITUTION.DEFAULT_CURRENCY, "HNL")
                    .set(ORGANIZATION_INSTITUTION.LOCALE, "es-HN")
                    .set(ORGANIZATION_INSTITUTION.TIMEZONE, "America/Tegucigalpa")
                    .set(ORGANIZATION_INSTITUTION.IS_ACTIVE, true)
                    .execute();
            return null;
        });
        return institutionId;
    }

    private String legalNameOf(InstitutionId institutionId) {
        return transactionRunner().execute(contextOf(institutionId),
                () -> dsl.selectFrom(ORGANIZATION_INSTITUTION)
                        .where(ORGANIZATION_INSTITUTION.ID.eq(institutionId.value()))
                        .fetchOne(ORGANIZATION_INSTITUTION.LEGAL_NAME));
    }

    private static long countPlusSuffixes(String legalName) {
        return legalName.chars().filter(c -> c == '+').count();
    }

    private static void awaitUninterruptibly(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("both sides of the race must reach the barrier", e);
        }
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}
