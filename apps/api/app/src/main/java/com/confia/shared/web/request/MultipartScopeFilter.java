package com.confia.shared.web.request;

import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts {@code multipart/*} only on the path prefixes it is given; on any other path the request
 * is answered {@code 415 unsupported-media-type} before the security chain, the rate limiter or the dispatcher see it,
 * and without reading a byte of the body. Together with {@code spring.servlet.multipart.resolve-lazily} (the parts of
 * an accepted route are parsed only when its handler asks for them, after authentication, authorization and the rate
 * limit), an upload cannot make the server work on a route that has no use for it, authenticated or not.
 *
 * <p>The path is the servlet path, which the container has already normalized, so {@code /a/../b} is judged as
 * {@code /b}.
 */
public final class MultipartScopeFilter extends OncePerRequestFilter implements Ordered {

    private final ProblemResponses problems;
    private final List<String> prefixes;

    public MultipartScopeFilter(ProblemResponses problems, List<String> prefixes) {
        this.problems = problems;
        this.prefixes = List.copyOf(prefixes);
    }

    @Override
    public int getOrder() {
        // Right after RequestContextFilter (HIGHEST_PRECEDENCE + 10), so the refusal carries the request id.
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (isMultipart(request) && !accepted(request)) {
            problems.write(request, response, ProblemCode.UNSUPPORTED_MEDIA_TYPE);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isMultipart(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    private boolean accepted(HttpServletRequest request) {
        String path = request.getServletPath();
        String info = request.getPathInfo();
        String full = info == null ? path : path + info;
        return prefixes.stream().anyMatch(full::startsWith);
    }
}
