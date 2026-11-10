package com.confia.shared.web.authorization;

import com.confia.kernel.Permission;

/**
 * The signals the authorization of the administrative chain emits, declared here so the decision
 * never depends on the adapter that writes them (rbac-permission-matrix-and-audit-integration design.md
 * D11). Until the observability change replaces the adapter with the Prometheus one, it writes a log
 * event. A signal carries nothing of the request, the actor or the failure: the only datum any method
 * accepts is a {@link Permission}, a closed catalog, so no email, address or route can reach an adapter.
 */
public interface AuthorizationMetrics {

    /** A denial was not written as a full audit row because the cap of its window was spent. */
    void denialOmitted(Permission permission);

    /** The audit row of a denial could not be written; the response is still the {@code 403}. */
    void denialAuditFailed();

    /** The permissions of an actor could not be read, and the request ended in an error. */
    void resolutionFailed();

    /** The denial tracker is at its bound and a new key could not be tracked. */
    void denialTrackerFull();
}
