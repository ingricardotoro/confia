package com.confia.shared.web.delay;

import com.confia.shared.web.problem.CapacityExceededException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Waits the delay a use case requires, after the use case has committed and without holding
 * anything but a virtual thread (web-edge-foundations design.md, decision 18). It is the one place
 * a thread of this system blocks on purpose (rule W4, {@code BlockingWaitConfinementTest}): the
 * answer of an authentication endpoint is delayed so the time it takes tells a client nothing.
 *
 * <p>The flow of {@link #execute}, in this order:
 *
 * <ol>
 *   <li>A permit of the semaphore is taken <b>before</b> the use case runs, without waiting for
 *       one. With none left the request is refused with {@link CapacityExceededException} ({@code
 *       503}) and the use case never runs: no effect, no failure to record.
 *   <li>The use case runs, opens and commits its own transaction, and returns. On return there is
 *       no transaction, no pooled connection and no row lock, which {@link
 *       TransactionSynchronizationManager#isActualTransactionActive()} guards a second time.
 *   <li>A delay of zero or less answers at once. A positive one is waited in full from this
 *       instant: it adds to the processing time and never absorbs it.
 *   <li>The permit is released in a {@code finally}, whatever happened: a failing use case, an
 *       interrupted wait, or an answer that cannot be written because the client left.
 * </ol>
 *
 * <p><b>A client that leaves.</b> Tomcat does not report a closed connection while a request waits
 * (probe P3), so the wait always runs to its end and the permit comes back only then. What makes
 * that harmless is what follows: the write of the answer fails, the translator of exceptions
 * recognizes a client that went away and writes nothing, and nothing is logged as an error. This
 * class logs nothing at all.
 *
 * <p><b>Thread.</b> The wait blocks the current thread, which must be virtual ({@code
 * spring.threads.virtual.enabled}); a platform thread is refused before the use case runs, because
 * the use case would commit and then the wait would hold a thread of the server's small pool.
 */
public final class RequiredDelayMaterializer {

    private final Semaphore permits;
    private final DelayTimer timer;

    /** The materializer of production: its wait is {@link Thread#sleep(Duration)}. */
    public RequiredDelayMaterializer(int maxConcurrentWaits) {
        this(maxConcurrentWaits, delay -> Thread.sleep(delay));
    }

    /** For tests, which hold and release the wait by hand. */
    public RequiredDelayMaterializer(int maxConcurrentWaits, DelayTimer timer) {
        if (maxConcurrentWaits <= 0) {
            throw new IllegalArgumentException(
                    DelayProperties.MAX_CONCURRENT_WAITS + " must be positive");
        }
        this.permits = new Semaphore(maxConcurrentWaits);
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    /**
     * Runs {@code useCase} and waits the delay it returns.
     *
     * @return the value of the {@link Delayed} the use case returned
     * @throws CapacityExceededException when every permit is taken; the use case did not run
     * @throws IllegalStateException when this thread is not virtual, when a transaction is active,
     *     or when the wait is interrupted
     */
    public <T> T execute(Supplier<Delayed<T>> useCase) {
        if (!Thread.currentThread().isVirtual()) {
            throw new IllegalStateException("the delay is waited on a virtual thread and this "
                    + "thread is not one");
        }
        requireNoTransaction();
        if (!permits.tryAcquire()) {
            throw new CapacityExceededException();
        }
        try {
            Delayed<T> answer = Objects.requireNonNull(useCase.get(),
                    "the use case answered nothing");
            Duration delay = answer.requiredDelay();
            if (!delay.isZero() && !delay.isNegative()) {
                requireNoTransaction();
                waitFor(delay);
            }
            return answer.value();
        } finally {
            permits.release();
        }
    }

    /** The permits not in use, which is all of them when nothing is waiting. */
    public int availablePermits() {
        return permits.availablePermits();
    }

    private void waitFor(Duration delay) {
        try {
            timer.await(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the wait for the required delay was interrupted");
        }
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "the delay must be materialized outside any transaction");
        }
    }
}
