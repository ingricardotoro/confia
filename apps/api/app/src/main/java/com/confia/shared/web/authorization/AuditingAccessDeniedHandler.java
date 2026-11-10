package com.confia.shared.web.authorization;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import com.confia.shared.web.problem.ProblemResponses;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Decorates the handler of the {@code 403} of the administrative chain so that a denial by permission
 * leaves its audit trace before the answer is written (rbac-permission-matrix-and-audit-integration
 * design.md D8). Only a {@link PermissionDeniedException} is recorded: every other {@code 403} of the
 * chain (an unmapped route, a missing one-time password) is answered as before and leaves no entry. The
 * answer always comes from the delegate, so it is the same body whether the trace was written or not.
 * The portal keeps the plain handler and carries nothing of this package.
 */
public final class AuditingAccessDeniedHandler implements AccessDeniedHandler {

    private final AccessDeniedHandler delegate;
    private final PermissionDenialRecorder recorder;

    public AuditingAccessDeniedHandler(AccessDeniedHandler delegate,
            PermissionDenialRecorder recorder) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException denied) throws IOException {
        if (denied instanceof PermissionDeniedException permissionDenied) {
            recorder.record(permissionDenied, requestIdOf(request));
        }
        try {
            delegate.handle(request, response, denied);
        } catch (jakarta.servlet.ServletException failure) {
            throw new IOException("the answer of the denial could not be written");
        }
    }

    /** The server's request id, which the problem repeats as its {@code traceId}. */
    private static String requestIdOf(HttpServletRequest request) {
        Object id = request.getAttribute(ProblemResponses.REQUEST_ID_ATTRIBUTE);
        return id instanceof String text ? text : UUID.randomUUID().toString();
    }
}
