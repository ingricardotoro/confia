package com.confia.shared.web.request;

import com.confia.shared.security.RequestOrigin;
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

    private static final String USER_AGENT = "User-Agent";

    private final ProblemResponses problems;
    private final ClientAddressResolver addresses;
    private final int userAgentMaxLength;

    public RequestContextFilter(ProblemResponses problems, WebEdgeProperties properties) {
        this.problems = problems;
        this.addresses = new ClientAddressResolver(
                TrustedProxies.parse(properties.trustedProxies()));
        this.userAgentMaxLength = properties.userAgentMaxLength();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        UUID requestId = UUID.randomUUID();
        request.setAttribute(ProblemResponses.REQUEST_ID_ATTRIBUTE, requestId.toString());
        MDC.put(MDC_KEY, requestId.toString());
        RequestOrigin origin = new RequestOrigin(requestId,
                addresses.resolve(request).orElse(null), userAgentOf(request));
        try {
            callWithOrigin(origin, request, response, chain);
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

    /**
     * Runs the rest of the chain with {@code origin} bound, and only for the length of that call.
     * Checked exceptions of the chain come out as they went in.
     */
    private static void callWithOrigin(RequestOrigin origin, HttpServletRequest request,
            HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        try {
            ScopedValue.where(RequestOrigin.CURRENT, origin).call(() -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // The call is declared to throw Exception; the chain throws nothing but the three above.
            throw new IllegalStateException(e);
        }
    }

    /**
     * The {@code User-Agent} header cut to its limit, or {@code null} when there is none. Control
     * characters become spaces, because a NUL would break the {@code TEXT} column that stores it,
     * and a cut never leaves the first half of a surrogate pair.
     */
    private String userAgentOf(HttpServletRequest request) {
        String raw = request.getHeader(USER_AGENT);
        if (raw == null) {
            return null;
        }
        int end = Math.min(raw.length(), userAgentMaxLength);
        if (end < raw.length() && Character.isHighSurrogate(raw.charAt(end - 1))) {
            end--;
        }
        StringBuilder kept = new StringBuilder(end);
        for (int i = 0; i < end; i++) {
            char c = raw.charAt(i);
            kept.append(Character.isISOControl(c) ? ' ' : c);
        }
        return kept.toString();
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
