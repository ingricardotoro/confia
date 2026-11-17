/**
 * The administrative process's registration of the {@code charges} module: {@link
 * com.confia.charges.infrastructure.wiring.ChargesConfiguration} declares, explicitly, the jOOQ adapters of the charge
 * plans and the use case over them (docs/cortes/charges-plans.md).
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface names the real consumer
 * that justifies it). The consumer is {@code bootstrap.admin.AdminApplication}, which {@code @Import}s the configuration
 * explicitly, with no component scan (ADR-0024). The portal and the worker never load it. The interface is this nested
 * package and not {@code charges.infrastructure} itself, so the adapters stay internal to the module and only the
 * configuration is public.
 */
@org.springframework.modulith.NamedInterface
package com.confia.charges.infrastructure.wiring;
