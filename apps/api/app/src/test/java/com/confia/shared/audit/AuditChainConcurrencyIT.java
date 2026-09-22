package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

/**
 * Concurrency of the hash chain (design.md §7.2, "Concurrencia del disparador";
 * specs/audit-trail/spec.md, requirement "Cadena de hash por institución con registro génesis",
 * scenario "Inserciones concurrentes de dos transacciones confirmadas encadenan sin condición de
 * carrera").
 *
 * <p>Two independent {@link TransactionRunner} instances, each on its own connection (never sharing
 * one, exactly like {@link com.confia.shared.security.TransactionRunnerRetryIT}'s own pattern),
 * synchronized with a {@link CyclicBarrier} right after their transaction opens and before either
 * inserts, so both are genuinely racing to write for the same institution — never a sequential
 * fixture that could pass by accident. Neither thread is expected to fail or retry: the row lock
 * {@code shared_audit_log_chain()} takes on {@code shared_audit_chain_head} (design.md decision 4,
 * confirmed by sonda S4: a second writer blocked 4407 ms in real measurement) simply makes the
 * second thread wait under ordinary {@code READ COMMITTED}, not raise a serialization conflict.
 */
class AuditChainConcurrencyIT extends CommittingPostgresIntegrationTest {

    @Test
    void concurrentCommittedInsertsForTheSameInstitutionChainWithoutARaceCondition()
            throws Exception {
        UUID institutionId = UUID.randomUUID();
        seedFirstRow(institutionId);

        CyclicBarrier bothTransactionsOpenBeforeEitherInserts = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AuditRow> taskA = executor.submit(insertTask(newIndependentRunner(), institutionId,
                    "actor A", bothTransactionsOpenBeforeEitherInserts));
            Future<AuditRow> taskB = executor.submit(insertTask(newIndependentRunner(), institutionId,
                    "actor B", bothTransactionsOpenBeforeEitherInserts));

            AuditRow rowA = taskA.get(30, TimeUnit.SECONDS);
            AuditRow rowB = taskB.get(30, TimeUnit.SECONDS);

            List<AuditRow> ordered = rowA.id() < rowB.id() ? List.of(rowA, rowB) : List.of(rowB, rowA);
            AuditRow earlier = ordered.get(0);
            AuditRow later = ordered.get(1);

            assertThat(earlier.id())
                    .as("the two concurrent inserts must be assigned consecutive ids, right after "
                            + "the seeded genesis row")
                    .isEqualTo(2L);
            assertThat(later.id())
                    .as("no gap and no collision between the two concurrent ids")
                    .isEqualTo(3L);
            assertThat(later.prevHash())
                    .as("the later of the two concurrent inserts must chain against the earlier "
                            + "one's row_hash, proving the institution-level lock serialized them "
                            + "instead of letting both compute prev_hash from the same stale row")
                    .isEqualTo(earlier.rowHash());
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<AuditRow> insertTask(TransactionRunner runner, UUID institutionId,
            String actorLabel, CyclicBarrier barrier) {
        return () -> runner.execute(contextOf(institutionId), () -> {
            awaitUninterruptibly(barrier);
            Record record = dsl.fetchOne("""
                    insert into shared_audit_log
                        (institution_id, actor_kind, actor_label, request_id, action, entity_type,
                         entity_id, outcome)
                    values (?, 'system', ?, ?, 'test.action', 'test_entity', 'entity-1', 'success')
                    returning id, prev_hash, row_hash
                    """, institutionId, actorLabel, UUID.randomUUID());
            return toAuditRow(record);
        });
    }

    private void seedFirstRow(UUID institutionId) {
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into shared_audit_log
                        (institution_id, actor_kind, actor_label, request_id, action, entity_type,
                         entity_id, outcome)
                    values (?, 'system', 'seed actor', ?, 'test.action', 'test_entity', 'entity-1',
                        'success')
                    """, institutionId, UUID.randomUUID());
            return null;
        });
    }

    private TransactionRunner newIndependentRunner() {
        return new TransactionRunner(transactionManager(), dataSource());
    }

    private static AuditRow toAuditRow(Record record) {
        return new AuditRow(record.get("id", Long.class), record.get("prev_hash", byte[].class),
                record.get("row_hash", byte[].class));
    }

    private static void awaitUninterruptibly(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("both concurrent inserts must reach the barrier", e);
        }
    }

    private static SecurityContext contextOf(UUID institutionId) {
        return new SecurityContext("", "system", institutionId.toString(),
                UUID.randomUUID().toString());
    }

    private record AuditRow(long id, byte[] prevHash, byte[] rowHash) {
    }
}
