package com.confia.shared.web.harness;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the harness controllers observed, so a test can prove a denied request reached no
 * controller at all (web-edge spec: "el controlador registra cero invocaciones").
 */
public final class Calls {

    private final AtomicInteger protectedRoute = new AtomicInteger();
    private final AtomicInteger openRoute = new AtomicInteger();

    /** Invocations of the registered route that is not on the public allow-list. */
    public int protectedInvocations() {
        return protectedRoute.get();
    }

    /** Invocations of the registered route the harness added to the allow-list. */
    public int openInvocations() {
        return openRoute.get();
    }

    void protectedInvoked() {
        protectedRoute.incrementAndGet();
    }

    void openInvoked() {
        openRoute.incrementAndGet();
    }
}
