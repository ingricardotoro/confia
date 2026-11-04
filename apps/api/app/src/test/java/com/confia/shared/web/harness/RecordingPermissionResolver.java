package com.confia.shared.web.harness;

import com.confia.kernel.Permission;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.PermissionResolver;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The double of the permission resolver port for the database-free harness (rbac-permission-matrix-
 * and-audit-integration design.md D19): it counts its invocations, answers per account what the test
 * granted, and can fail the way a broken database would.
 */
public final class RecordingPermissionResolver implements PermissionResolver {

    private final AtomicInteger invocations = new AtomicInteger();
    private final Map<UUID, Set<Permission>> grants = new ConcurrentHashMap<>();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    @Override
    public Set<Permission> resolve(AuthenticatedActor actor) {
        invocations.incrementAndGet();
        RuntimeException toThrow = failure.get();
        if (toThrow != null) {
            throw toThrow;
        }
        return grants.getOrDefault(actor.accountId(), Set.of());
    }

    /** How many times the port was asked. */
    public int invocations() {
        return invocations.get();
    }

    /** From now on {@code account} holds exactly {@code permissions}. */
    public void grant(UUID account, Permission... permissions) {
        grants.put(account, Set.of(permissions));
    }

    /** From now on the port fails with {@code failure}. */
    public void fail(RuntimeException failure) {
        this.failure.set(failure);
    }

    /** The port answers again; the grants stay. */
    public void recover() {
        failure.set(null);
    }
}
