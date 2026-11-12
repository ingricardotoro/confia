/**
 * The administrative process's registration of the {@code guardians} module: {@link
 * com.confia.guardians.infrastructure.wiring.GuardiansConfiguration} declares, explicitly, the jOOQ adapters, the
 * protector of the national identity document and the RTN and the two use cases (docs/cortes/guardians-registry.md).
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface names the real
 * consumer that justifies it). The consumer is {@code bootstrap.admin.AdminApplication}, which {@code @Import}s the
 * configuration explicitly, with no component scan (ADR-0024). The portal and the worker never load it. The
 * interface is this nested package and not {@code guardians.infrastructure} itself, so the adapters stay internal to
 * the module and only the configuration is public.
 */
@org.springframework.modulith.NamedInterface
package com.confia.guardians.infrastructure.wiring;
