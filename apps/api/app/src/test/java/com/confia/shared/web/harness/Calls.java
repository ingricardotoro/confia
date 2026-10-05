package com.confia.shared.web.harness;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the harness controllers observed, so a test can prove a denied request reached no
 * controller at all (web-edge spec: "el controlador registra cero invocaciones") and can compare
 * the request id a controller saw with the one the error body carries.
 */
public final class Calls {

    private final AtomicInteger protectedRoute = new AtomicInteger();
    private final AtomicInteger openRoute = new AtomicInteger();
    private final AtomicReference<String> requestIdAttribute = new AtomicReference<>();
    private final AtomicReference<String> requestIdInMdc = new AtomicReference<>();

    /** Invocations of the registered route that is not on the public allow-list. */
    public int protectedInvocations() {
        return protectedRoute.get();
    }

    /** Invocations of the registered route the harness added to the allow-list. */
    public int openInvocations() {
        return openRoute.get();
    }

    /** The request id attribute the last invocation of the failing route saw. */
    public String lastRequestIdAttribute() {
        return requestIdAttribute.get();
    }

    /** The {@code requestId} MDC entry the last invocation of the failing route saw. */
    public String lastRequestIdInMdc() {
        return requestIdInMdc.get();
    }

    void protectedInvoked() {
        protectedRoute.incrementAndGet();
    }

    void openInvoked() {
        openRoute.incrementAndGet();
    }

    void failingInvoked(String attribute, String mdc) {
        requestIdAttribute.set(attribute);
        requestIdInMdc.set(mdc);
    }
}
