package com.confia.identity.infrastructure;

import com.confia.identity.application.LoginFailureSpread;
import com.confia.identity.domain.FailureSpreadWindow;
import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.shared.security.ClientAddress;
import com.confia.shared.security.ClientKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps one {@link FailureSpreadWindow} per client key in memory
 * (session-endpoints-cookie-and-csrf design.md decision 12). The key is the one of the rate limiter,
 * {@link ClientAddress#rateLimitKey()}: the address for IPv4 and the /64 prefix for IPv6, so the two
 * controls look at the same client.
 *
 * <p><b>It is a signal, not a control.</b> The map is bounded: when it holds {@code maxClients}
 * windows and a new address arrives, the windows with nothing live are dropped first; if none is,
 * the new address is neither recorded nor signalled, and the windows already tracked keep counting.
 * Failing to see a spread is acceptable (the rate limiter is the control); growing without bound
 * under an attack that rotates addresses is not.
 *
 * <p>Same three limits as the in-memory rate limiter, declared: the state is per process, it is lost
 * on restart and it is not shared between replicas. Redis replaces it in change 11. Every call is
 * serialized on one monitor; each call is a constant amount of work over at most the identifiers one
 * address failed with in ten minutes.
 */
public final class InMemoryLoginFailureSpread implements LoginFailureSpread {

    private final int maxClients;
    private final Map<ClientKey, FailureSpreadWindow> windows = new HashMap<>();

    /**
     * @param maxClients the most client keys tracked at once; at least one
     */
    public InMemoryLoginFailureSpread(int maxClients) {
        if (maxClients < 1) {
            throw new IllegalArgumentException("maxClients must be at least 1, was " + maxClients);
        }
        this.maxClients = maxClients;
    }

    @Override
    public synchronized boolean recordFailure(ClientAddress client, IdentifierFingerprint identifier,
            Instant now) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(now, "now");
        ClientKey key = client.rateLimitKey();
        FailureSpreadWindow window = windows.get(key);
        if (window == null) {
            if (windows.size() >= maxClients) {
                dropWindowsWithNothingLive(now);
            }
            if (windows.size() >= maxClients) {
                return false;
            }
            window = new FailureSpreadWindow();
            windows.put(key, window);
        }
        return window.record(identifier, now);
    }

    /** How many client keys are tracked now; for tests and for operations. */
    public synchronized int trackedClients() {
        return windows.size();
    }

    private void dropWindowsWithNothingLive(Instant now) {
        Iterator<FailureSpreadWindow> iterator = windows.values().iterator();
        while (iterator.hasNext()) {
            if (!iterator.next().hasLiveFailures(now)) {
                iterator.remove();
            }
        }
    }
}
