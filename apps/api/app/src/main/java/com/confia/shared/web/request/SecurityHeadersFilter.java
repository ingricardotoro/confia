package com.confia.shared.web.request;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sets the base security headers on every response of the administrative and the portal process
 * (web-edge-foundations design.md, decision 7; docs/03-seguridad.md section 8.2). It is the first
 * filter, so the headers are already there when the firewall, the chain, the rate limiter or the
 * last resort writes a response, and Spring Security's own header writer is disabled so there is
 * one owner and no second, different {@code Cache-Control}.
 *
 * <p>{@code Strict-Transport-Security} and {@code Cross-Origin-Embedder-Policy} belong to the
 * proxy (change 11). {@code X-Powered-By} and {@code Server} are never emitted.
 */
public final class SecurityHeadersFilter extends OncePerRequestFilter implements Ordered {

    private static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
