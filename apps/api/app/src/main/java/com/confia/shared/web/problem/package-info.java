/**
 * The Problem Details (RFC 9457) of the web edge (web-edge-foundations design.md, decision 8): the
 * stable codes, the writer both the security chain and the translator use, and the exception a
 * controller throws to end a request with a code of the catalog.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface of
 * {@code shared} names the real consumer that justifies it). The consumer is {@code
 * identity.web.LoginController}, which throws {@link
 * com.confia.shared.web.problem.ProblemCodeException} for {@code authentication-failed}
 * (session-endpoints-cookie-and-csrf design.md, decisions 11 and 18).
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.web.problem;
