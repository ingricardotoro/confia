package com.confia.shared.web.harness;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts the HTTP sessions the container creates, to prove the chain never creates one. */
public final class SessionCounter implements HttpSessionListener {

    private final AtomicInteger created = new AtomicInteger();

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        created.incrementAndGet();
    }

    /** The number of sessions created since the harness started. */
    public int created() {
        return created.get();
    }
}
