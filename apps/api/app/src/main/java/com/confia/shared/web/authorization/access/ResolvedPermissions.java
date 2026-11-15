package com.confia.shared.web.authorization.access;

import com.confia.kernel.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Set;

/**
 * The permissions the decider of the edge resolved for the request, carried on the request itself so that nobody
 * resolves them a second time. The decider stores them with {@link #store}; a controller reads them with {@link #of}.
 */
public final class ResolvedPermissions {

    /** The request attribute that holds the {@code Set<Permission>}. */
    public static final String ATTRIBUTE = ResolvedPermissions.class.getName();

    private ResolvedPermissions() {
    }

    /** Called by the decider after it resolved the permissions of the actor. */
    public static void store(HttpServletRequest request, Set<Permission> permissions) {
        Objects.requireNonNull(request, "request").setAttribute(ATTRIBUTE, Set.copyOf(permissions));
    }

    /**
     * The permissions of the request. Their absence is a defect of the wiring (the route was not decided by the
     * decider), never a client error.
     */
    public static Set<Permission> of(HttpServletRequest request) {
        Object stored = Objects.requireNonNull(request, "request").getAttribute(ATTRIBUTE);
        if (!(stored instanceof Set<?> set) || !set.stream().allMatch(Permission.class::isInstance)) {
            throw new IllegalStateException("the permission decider did not resolve the permissions of this request");
        }
        return set.stream().map(Permission.class::cast).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
