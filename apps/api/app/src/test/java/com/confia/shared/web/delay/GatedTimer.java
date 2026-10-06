package com.confia.shared.web.delay;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The timer of the tests of the delay materializer: it records every wait it is asked for and, when
 * gated, holds each one until the test opens the gate, so that "the wait is in progress" is a state
 * the test can reach and keep without any clock (web-edge-foundations tasks.md, "Concurrencia
 * determinista"). It reports the thread that waits, to prove that thread is virtual.
 */
final class GatedTimer implements DelayTimer {

    private static final long SAFETY_SECONDS = 30;

    private final CountDownLatch gate;
    private final CountDownLatch entered;
    private final List<Duration> requested = new CopyOnWriteArrayList<>();
    private final List<Boolean> virtual = new CopyOnWriteArrayList<>();

    private GatedTimer(CountDownLatch gate, int expectedWaiters) {
        this.gate = gate;
        this.entered = new CountDownLatch(expectedWaiters);
    }

    /** A timer that returns at once and only records what it was asked for. */
    static GatedTimer immediate() {
        return new GatedTimer(new CountDownLatch(0), 0);
    }

    /** A timer that holds every wait until {@link #open()}; {@code expectedWaiters} may enter. */
    static GatedTimer gated(int expectedWaiters) {
        return new GatedTimer(new CountDownLatch(1), expectedWaiters);
    }

    @Override
    public void await(Duration delay) throws InterruptedException {
        requested.add(delay);
        virtual.add(Thread.currentThread().isVirtual());
        entered.countDown();
        if (!gate.await(SAFETY_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the test never opened the gate");
        }
    }

    /** Returns once the expected number of waits is in progress. */
    void awaitEntered() throws InterruptedException {
        if (!entered.await(SAFETY_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the expected waits never began");
        }
    }

    /** Lets every held wait, present and future, return. */
    void open() {
        gate.countDown();
    }

    /** Every duration a wait was requested for, in the order they arrived. */
    List<Duration> requested() {
        return List.copyOf(requested);
    }

    /** Whether each wait ran on a virtual thread. */
    List<Boolean> virtualThreads() {
        return List.copyOf(virtual);
    }
}
