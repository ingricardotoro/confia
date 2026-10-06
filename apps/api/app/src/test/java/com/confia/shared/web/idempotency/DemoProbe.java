package com.confia.shared.web.idempotency;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the demonstration use case and the record store of the database harness report to the test,
 * and the two gates a test closes to hold a request where it wants it. Only latches decide the
 * order of events: no test waits for a clock.
 */
final class DemoProbe {

    static final long SAFETY_SECONDS = 30;

    private final AtomicInteger invocations = new AtomicInteger();
    private final AtomicInteger markerInserts = new AtomicInteger();
    private volatile CountDownLatch insideUseCase = new CountDownLatch(1);
    private volatile CountDownLatch secondMarkerInsert = new CountDownLatch(1);
    private volatile CountDownLatch release = new CountDownLatch(0);

    /** Back to a fresh state: nothing counted, nothing held. */
    void reset() {
        invocations.set(0);
        markerInserts.set(0);
        insideUseCase = new CountDownLatch(1);
        secondMarkerInsert = new CountDownLatch(1);
        release = new CountDownLatch(0);
    }

    /** From now on the use case stays open, after its marker is written, until {@link #release()}. */
    void holdTheUseCase() {
        release = new CountDownLatch(1);
    }

    void release() {
        release.countDown();
    }

    int invocations() {
        return invocations.get();
    }

    /** Called by the use case: counts, tells the test it is inside and waits for the release. */
    void useCaseRan() {
        invocations.incrementAndGet();
        insideUseCase.countDown();
        try {
            if (!release.await(SAFETY_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the use case");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while held", e);
        }
    }

    /** Called by the store decorator just before every marker insert. */
    void markerInsertAttempted() {
        if (markerInserts.incrementAndGet() == 2) {
            secondMarkerInsert.countDown();
        }
    }

    boolean awaitInsideTheUseCase() throws InterruptedException {
        return insideUseCase.await(SAFETY_SECONDS, TimeUnit.SECONDS);
    }

    boolean awaitSecondMarkerInsert() throws InterruptedException {
        return secondMarkerInsert.await(SAFETY_SECONDS, TimeUnit.SECONDS);
    }
}
