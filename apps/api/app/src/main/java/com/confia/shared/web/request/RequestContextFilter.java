package com.confia.shared.web.request;

import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id the server generates, before anything else can answer it
 * (web-edge-foundations design.md, decision 11). The id is a fresh UUID: {@code X-Request-Id},
 * {@code traceparent} and any other header of the client are ignored, because a client that could
 * choose the id would choose what appears in the audit trail. It is kept in the request attribute
 * {@link ProblemResponses#REQUEST_ID_ATTRIBUTE}, which the Problem Details writer reads as
 * {@code traceId}, and in the logging context under {@value #MDC_KEY}, which is removed when the
 * request ends so a pooled thread never carries it to the next request.
 *
 * <p>It runs ahead of the security chain, so even the chain's own denials carry the id, and it is
 * the last resort: an exception that escapes everything else, on a response not yet committed,
 * becomes a {@code 500 internal-error} with nothing internal in it, and the full exception goes
 * only to the server log with the id.
 */
public final class RequestContextFilter extends OncePerRequestFilter implements Ordered {

    /** The logging context key that holds the id of the request. */
    public static final String MDC_KEY = "requestId";

    private static final Logger LOG = LoggerFactory.getLogger(RequestContextFilter.class);

    private final ProblemResponses problems;

    public RequestContextFilter(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ProblemResponses.REQUEST_ID_ATTRIBUTE, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            if (response.isCommitted()) {
                throw e;
            }
            LOG.error("An exception escaped the request chain", e);
            answerWithTheLastResort(request, response, e);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private void answerWithTheLastResort(HttpServletRequest request, HttpServletResponse response,
            Exception cause) throws IOException {
        try {
            response.resetBuffer();
            problems.write(request, response, ProblemCode.INTERNAL_ERROR);
        } catch (IOException | RuntimeException writeFailure) {
            writeFailure.addSuppressed(cause);
            throw writeFailure;
        }
    }
}
