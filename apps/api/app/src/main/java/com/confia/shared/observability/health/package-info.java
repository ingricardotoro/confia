/**
 * The health probes of the two web processes (dockerfiles-and-local-compose design.md decision 4):
 * the routes {@code GET /health/live} and {@code GET /health/ready} ({@link
 * com.confia.shared.observability.health.HealthProbeRoutesConfiguration}, {@link com.confia.shared.observability.health.ReadinessDatabaseHealthConfiguration}). Actuator is present as the
 * health module only: no metrics, no {@code MeterRegistry} and no {@code /actuator/**} route.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface of
 * {@code shared} names the real consumer that justifies it). The consumers are the two web entry points,
 * {@code bootstrap.admin.AdminApplication} and {@code bootstrap.portal.PortalApplication}, which
 * {@code @Import} the configurations of this package explicitly, with no component scan (ADR-0024). The
 * worker does not load it: its health is a heartbeat file, not a route.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.observability.health;
