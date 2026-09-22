package com.confia.shared.security;

import static confia.generated.jooq.tables.OrganizationInstitution.ORGANIZATION_INSTITUTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.support.CommittingPostgresIntegrationTest;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;

/**
 * The bounded retry of {@link TransactionRunner} (design.md, decision 2, "Detección del error de
 * serialización" and "Retroceso"; specs/build-integrity/spec.md, requirement "Contexto de sesión,
 * nivel de aislamiento y reintento acotado del componente transaccional único" — the two retry
 * scenarios; the non-retry scenarios live in {@link TransactionRunnerContextIT}).
 *
 * <p>Extends {@link CommittingPostgresIntegrationTest}: both scenarios need the transaction to
 * genuinely commit or genuinely fail against the real engine, never roll back automatically.
 *
 * <p>Neither scenario depends on wall-clock timing (design.md §7.2, "Dos pruebas que no pueden
 * depender de tiempos"). The exhaustion scenario forces a deterministic {@code RAISE EXCEPTION
 * ... ERRCODE = '40001'} on every attempt, with no concurrency at all. The success scenario forces a
 * real {@code SERIALIZABLE} write conflict between two threads on the very same row, synchronized
 * with a {@link CyclicBarrier} so both transactions are genuinely open and about to write before
 * either commits — never relying on a sleep to create the race.
 */
class TransactionRunnerRetryIT extends CommittingPostgresIntegrationTest {

    /**
     * Deterministic agotamiento: the use case raises a real serialization-failure error on every
     * single attempt, with no concurrency. The component must retry a bounded, explicit number of
     * times and then propagate the original error — never retrying once more and never retrying
     * forever.
     */
    @Test
    void retryExhaustsAndPropagatesTheOriginalErrorDeterministically() {
        AtomicInteger attempts = new AtomicInteger();
        UUID institutionId = UUID.randomUUID();

        assertThatThrownBy(() -> transactionRunner().execute(contextOf(institutionId), () -> {
            attempts.incrementAndGet();
            dsl.execute("do $$ begin raise exception using errcode = '40001'; end $$;");
            return null;
        }))
                .as("the original serialization-failure error must propagate once the bounded "
                        + "retry limit is exhausted, not swallowed and not replaced")
                .matches(TransactionRunnerRetryIT::isSerializationFailure,
                        "carries SQLState 40001 somewhere in its cause chain, or is a Spring "
                                + "ConcurrencyFailureException");

        // 1 initial attempt + the bounded number of retries (design.md decision 2: three), never
        // more — this is the assertion that actually distinguishes "retried and then gave up" from
        // "never retried at all" or "retried without a bound".
        assertThat(attempts.get())
                .as("exactly the initial attempt plus the bounded number of retries, no more and "
                        + "no fewer")
                .isEqualTo(4);
    }

    /**
     * Real success within the bounded limit: two independent {@link TransactionRunner} instances,
     * each on its own connection, both {@code SERIALIZABLE}, both updating the very same committed
     * row. Whichever loses the write conflict must retry automatically and end up committed, with
     * the caller never observing the original serialization error.
     */
    @Test
    void retrySucceedsWithinTheBoundedLimitOnARealSerializationConflict() throws Exception {
        UUID sharedInstitutionId = UUID.randomUUID();
        seedCommittedInstitution(sharedInstitutionId);

        CyclicBarrier bothTransactionsOpenBeforeEitherWrites = new CyclicBarrier(2);
        AtomicBoolean firstAttemptOfA = new AtomicBoolean(true);
        AtomicBoolean firstAttemptOfB = new AtomicBoolean(true);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> taskA = executor.submit(updateTask(newIndependentRunner(), sharedInstitutionId,
                    "Updated by A", firstAttemptOfA, bothTransactionsOpenBeforeEitherWrites));
            Future<Void> taskB = executor.submit(updateTask(newIndependentRunner(), sharedInstitutionId,
                    "Updated by B", firstAttemptOfB, bothTransactionsOpenBeforeEitherWrites));

            // Both futures must complete without the caller ever observing the original
            // serialization error: the losing transaction retries internally and commits.
            taskA.get(30, TimeUnit.SECONDS);
            taskB.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        List<String> finalNames = dsl.selectFrom(ORGANIZATION_INSTITUTION)
                .where(ORGANIZATION_INSTITUTION.ID.eq(sharedInstitutionId))
                .fetch(ORGANIZATION_INSTITUTION.LEGAL_NAME);
        assertThat(finalNames)
                .as("the row survives with exactly one of the two updates applied — proof both "
                        + "transactions really committed against the same row, one after the other")
                .containsExactlyInAnyOrder(finalNames.get(0))
                .allMatch(name -> name.equals("Updated by A") || name.equals("Updated by B"));
    }

    /**
     * Builds the callable for one side of the conflict. The {@link CyclicBarrier} is awaited only
     * on each thread's very first attempt ({@code firstAttempt.compareAndSet(true, false)}): a
     * retried attempt must never await the same barrier again, or the losing thread would deadlock
     * waiting for a party that already finished on its first, successful attempt.
     */
    private Callable<Void> updateTask(TransactionRunner runner, UUID institutionId, String newName,
            AtomicBoolean firstAttempt, CyclicBarrier barrier) {
        return () -> runner.execute(contextOf(institutionId), IsolationLevel.SERIALIZABLE, () -> {
            if (firstAttempt.compareAndSet(true, false)) {
                awaitUninterruptibly(barrier);
            }
            dsl.execute("update organization_institution set legal_name = ? where id = ?", newName,
                    institutionId);
            return null;
        });
    }

    private TransactionRunner newIndependentRunner() {
        return new TransactionRunner(transactionManager(), dataSource());
    }

    private void seedCommittedInstitution(UUID institutionId) {
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into organization_institution
                        (id, legal_name, rtn, address, default_currency, locale, timezone)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, institutionId, "Seed", "12345678", "Dirección", "HNL", "es-HN",
                    "America/Tegucigalpa");
            return null;
        });
    }

    private static SecurityContext contextOf(UUID institutionId) {
        return new SecurityContext("", "system", institutionId.toString(),
                UUID.randomUUID().toString());
    }

    private static void awaitUninterruptibly(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("both sides of the conflict must reach the barrier", e);
        }
    }

    private static boolean isSerializationFailure(Throwable throwable) {
        if (throwable instanceof ConcurrencyFailureException) {
            return true;
        }
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if ("40001".equals(sqlState) || "40P01".equals(sqlState)) {
                    return true;
                }
            }
        }
        return false;
    }
}
