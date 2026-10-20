package com.confia.shared.web.harness;

import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.SessionValidity;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The double of the session validity port for the database-free harness (session-tokens-and-web-layer
 * tasks.md 5.1, scenario WE28): it counts its invocations, remembers the actor of each one, and
 * answers what the test told it to, including a failure that stands for the database being down.
 */
public final class RecordingSessionValidity implements SessionValidity {

    private final AtomicInteger invocations = new AtomicInteger();
    private final List<AuthenticatedActor> actors = new CopyOnWriteArrayList<>();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private volatile boolean active = true;

    @Override
    public boolean isActive(AuthenticatedActor actor) {
        invocations.incrementAndGet();
        actors.add(actor);
        RuntimeException toThrow = failure.get();
        if (toThrow != null) {
            throw toThrow;
        }
        return active;
    }

    /** How many times the port was asked. */
    public int invocations() {
        return invocations.get();
    }

    /** The actor of every question, oldest first. */
    public List<AuthenticatedActor> actors() {
        return List.copyOf(actors);
    }

    /** From now on the port answers {@code active}. */
    public void answer(boolean active) {
        this.active = active;
        this.failure.set(null);
    }

    /** From now on the port fails with {@code failure}, as a broken database would. */
    public void fail(RuntimeException failure) {
        this.failure.set(failure);
    }
}
