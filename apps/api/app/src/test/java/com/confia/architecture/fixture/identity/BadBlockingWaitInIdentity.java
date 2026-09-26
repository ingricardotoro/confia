package com.confia.architecture.fixture.identity;

/**
 * Deliberate violation fixture (design.md, decision 10, point 4; specs/identity/spec.md,
 * "Ninguna clase del módulo de identidad espera"). Physically kept under
 * {@code architecture.fixture.identity} rather than {@code com.confia.identity} itself, so a real
 * production class never accidentally becomes this fixture — the same permanent-rejection pattern
 * every other architecture rule in this package already follows (see {@code
 * BadStandardStreamUsage}). {@link com.confia.architecture.NoBlockingWaitInIdentityTest} treats
 * this package as "simulating" {@code com.confia.identity} for the sole purpose of proving its rule
 * actually rejects a thread-blocking wait: it is what proves the rule rejects something, not merely
 * that it never finds a violation because it never looked.
 */
public final class BadBlockingWaitInIdentity {

    public void blocksTheCurrentThread() throws InterruptedException {
        Thread.sleep(1000);
    }
}
