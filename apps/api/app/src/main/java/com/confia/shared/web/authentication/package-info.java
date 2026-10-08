/**
 * Bearer authentication of the administrative chain (session-tokens-and-web-layer design.md,
 * decision 5): the filter that turns a verified access token into an {@link
 * com.confia.shared.security.AuthenticatedActor}, Spring's view of that actor, the closed list of
 * routes that ask for one, and the two exceptions the entry point tells apart.
 *
 * <p>The filter is the only class of the web layer allowed to depend on {@code
 * com.confia.shared.security.token} (rule BI14, with the configuration that builds it).
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface of
 * {@code shared} names the real consumer that justifies it). The consumers are the administrative
 * security configuration, which builds the filter and the list, and the entry point of the Problem
 * Details writer, which tells its two exceptions apart; both live in {@code shared.web}, and the
 * session endpoint of task 5.2 declares its route in the list.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.web.authentication;
