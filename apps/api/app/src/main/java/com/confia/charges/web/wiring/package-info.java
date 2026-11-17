/**
 * The administrative process's registration of the web layer of the {@code charges} module: {@link
 * com.confia.charges.web.wiring.ChargesWebConfiguration} declares, explicitly, the controller of the charge plans
 * (docs/cortes/charges-plans.md).
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface names the real consumer
 * that justifies it). The consumer is {@code bootstrap.admin.AdminApplication}, which {@code @Import}s the configuration
 * explicitly, with no component scan (ADR-0024). The portal and the worker never load it. The interface is this nested
 * package and not {@code charges.web} itself, so the controller and its DTO stay internal to the module and only the
 * configuration is public.
 */
@org.springframework.modulith.NamedInterface
package com.confia.charges.web.wiring;
