/**
 * The rate limiter at the web edge: the {@code @RateLimited} annotation a handler declares, the
 * interceptor that applies the policy before the handler runs, the registry of limiters by policy
 * and the recorder of failures (web-edge-foundations design.md, decision 17).
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface of
 * {@code shared} names the real consumer that justifies it). The consumer is {@code
 * identity.web.LoginController}, which declares {@code @RateLimited(policy = "admin-login")} and
 * records the failures of that policy through {@link
 * com.confia.shared.web.ratelimit.RateLimitFailures} (session-endpoints-cookie-and-csrf design.md,
 * decision 11; condition H1).
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.web.ratelimit;
