/**
 * The web edge of the administrative and the portal process: the configuration that registers the
 * request filters and the Problem Details catalog (web-edge-foundations design.md, decisions 1, 7, 8,
 * 10 and 11). The deny-by-default security chains and the allow-list of public routes join this
 * package in the security chain part of the same change.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface of
 * {@code shared} names the real consumer that justifies it). The consumers are the two web entry
 * points, {@code bootstrap.admin.AdminApplication} and {@code bootstrap.portal.PortalApplication},
 * which {@code @Import} {@link com.confia.shared.web.edge.WebEdgeConfiguration} explicitly, with no
 * component scan (ADR-0024). The worker never loads any of it.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.web.edge;
