package com.confia.shared.web.authorization;

/**
 * The signals the authorization of the administrative chain emits, declared here so the decision
 * never depends on the adapter that writes them (rbac-permission-matrix-and-audit-integration design.md
 * D11). Until the observability change replaces the adapter with the Prometheus one, it writes a log
 * event. A signal carries nothing of the request, the actor or the failure.
 */
public interface AuthorizationMetrics {

    /** The permissions of an actor could not be read, and the request ended in an error. */
    void resolutionFailed();
}
