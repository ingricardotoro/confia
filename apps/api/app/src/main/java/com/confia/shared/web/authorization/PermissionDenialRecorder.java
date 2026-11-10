package com.confia.shared.web.authorization;

/**
 * Writes the audit trace of one denial by permission (rbac-permission-matrix-and-audit-integration
 * design.md D8). One operation, so the harness without a database can replace it with a double and the
 * administrative process binds {@link PermissionDenialAudit}. An implementation never throws: the
 * answer of the chain is the {@code 403} whatever happens to the trace.
 */
public interface PermissionDenialRecorder {

    /**
     * Records the denial of {@code denied} for the request {@code requestId}, the server-generated
     * identifier that is also the {@code traceId} of the problem the client receives.
     */
    void record(PermissionDeniedException denied, String requestId);
}
