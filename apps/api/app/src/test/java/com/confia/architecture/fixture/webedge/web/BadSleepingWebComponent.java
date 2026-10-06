package com.confia.architecture.fixture.webedge.web;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Deliberate violation fixture (web-edge-foundations design.md, decision 20, rule W4;
 * specs/build-integrity, requirement "La espera bloqueante del retardo se confina al
 * materializador"): a class of a {@code web} package that blocks its thread with each of the four
 * primitives {@code BlockingWaitConfinementTest} forbids outside the delay materializer. Permanent,
 * never removed: it is what proves that rule rejects something (ADR-0018).
 */
public final class BadSleepingWebComponent {

    public void sleepsTheThread() throws InterruptedException {
        Thread.sleep(1000);
    }

    public void sleepsOnATimeUnit() throws InterruptedException {
        TimeUnit.SECONDS.sleep(1);
    }

    public void parksTheThread() {
        LockSupport.parkNanos(1_000_000L);
    }

    public void waitsOnItsMonitor() throws InterruptedException {
        synchronized (this) {
            wait(1);
        }
    }
}
