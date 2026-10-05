package com.confia.shared.web.edge;

import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemResponses;
import com.confia.shared.web.request.SecurityHeadersFilter;
import java.io.IOException;
import org.apache.catalina.Container;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;

/**
 * The error report of the embedded Tomcat, replaced so that what the container refuses before any
 * filter runs ({@code /x%2f} and {@code /x%00}, which it rejects as {@code 400}, and {@code TRACE},
 * which it refuses as {@code 405}) is answered like every other error of the web edge:
 * Problem Details, the five base security headers and no {@code Server} or {@code X-Powered-By}
 * header (web-edge-foundations design.md, decisions 7 and 8; the gap assigned to task 2.2).
 *
 * <p>Nothing of the container reaches the client: neither the exception, whose text could name a
 * class or a path, nor the request path, which the container refused to decode and which {@code
 * instance} therefore does not repeat. Headers the container already set, such as the {@code
 * Allow} of a refused {@code TRACE}, are kept.
 *
 * <p>A response that already has a body, or that was already reported, is left alone, exactly as
 * Tomcat's own report does.
 */
public final class ProblemErrorReportValve extends ErrorReportValve {

    private final ProblemResponses problems;

    ProblemErrorReportValve(ProblemResponses problems) {
        this.problems = problems;
    }

    /**
     * Makes this valve the host's error report. The host adds a default one at startup unless a
     * valve of the configured class is already in its pipeline, so the class name is set to this
     * one and an instance, which holds the writer and which a reflective creation could not,
     * is added first.
     */
    static void install(Container parent, ProblemResponses problems) {
        if (!(parent instanceof StandardHost host)) {
            throw new IllegalStateException("the error report can only be replaced on a "
                    + "StandardHost, but the context's parent is "
                    + (parent == null ? "absent" : parent.getClass().getSimpleName()));
        }
        host.setErrorReportValveClass(ProblemErrorReportValve.class.getName());
        host.getPipeline().addValve(new ProblemErrorReportValve(problems));
    }

    /**
     * The code that answers a container status: {@code 405} is {@code method-not-allowed}, a
     * server error is {@code internal-error} and every other client error is {@code
     * validation-failed}. The answer carries the status of that code, which for a status the
     * catalog does not have (for example {@code 414}) is the nearest one it does.
     */
    public static ProblemCode codeFor(int status) {
        if (status == ProblemCode.METHOD_NOT_ALLOWED.status()) {
            return ProblemCode.METHOD_NOT_ALLOWED;
        }
        return status >= 500 ? ProblemCode.INTERNAL_ERROR : ProblemCode.VALIDATION_FAILED;
    }

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        if (response.getStatus() < 400 || response.getContentWritten() > 0
                || !response.setErrorReported()) {
            return;
        }
        try {
            response.resetBuffer();
            SecurityHeadersFilter.apply(response);
            problems.writeWithoutRequestPath(request, response, codeFor(response.getStatus()));
        } catch (IOException | IllegalStateException e) {
            // The client went away or the response was closed under us: there is nobody to
            // answer, and the container's own report ignores the same failure.
        }
    }
}
