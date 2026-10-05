package com.confia.shared.web.harness;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Injects an authenticated principal when the request carries {@value #HEADER}. The real chain has
 * no way to authenticate yet (that arrives with the session change), so the harness stands one in
 * to prove an authenticated principal outside the allow-list gets {@code 403}, not {@code 401}.
 */
final class TestPrincipalFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Test-Principal";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String name = request.getHeader(HEADER);
        if (name != null) {
            SecurityContext context = new SecurityContextImpl(
                    UsernamePasswordAuthenticationToken.authenticated(name, "n/a", List.of()));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }
}
