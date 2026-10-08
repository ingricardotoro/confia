/**
 * The administrative process's registration of the {@code organization} module: {@link
 * com.confia.organization.infrastructure.wiring.OrganizationConfiguration} declares, explicitly,
 * the adapter that resolves the current institution from the authenticated token
 * (session-tokens-and-web-layer design.md, decision 6; ADR-0009).
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface
 * names the real consumer that justifies it). The consumer is {@code
 * bootstrap.admin.AdminApplication}, which {@code @Import}s the configuration explicitly, with no
 * component scan (ADR-0024). The portal and the worker never load it. The interface is this nested
 * package and not {@code organization.infrastructure} itself, so the adapter stays internal to the
 * module and only the configuration is public.
 */
@org.springframework.modulith.NamedInterface
package com.confia.organization.infrastructure.wiring;
