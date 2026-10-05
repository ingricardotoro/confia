/**
 * The web edge of the administrative and the portal process: the deny-by-default security chains,
 * the closed allow-list of public routes and the configuration that registers the request filters
 * and the Problem Details catalog (web-edge-foundations design.md, decisions 1, 4, 5, 7, 8 and 10).
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface of
 * {@code shared} names the real consumer that justifies it). The consumers are the two web entry
 * points, {@code bootstrap.admin.AdminApplication} and {@code bootstrap.portal.PortalApplication},
 * which {@code @Import} {@link com.confia.shared.web.edge.WebEdgeConfiguration} and their own
 * security configuration explicitly, with no component scan (ADR-0024). The worker never loads any
 * of it.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.web.edge;
