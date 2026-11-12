/**
 * The administrative process's registration of the web layer of the {@code imports} module: {@link
 * com.confia.imports.web.wiring.ImportsWebConfiguration} declares, explicitly, the controller of the imports
 * (docs/cortes/imports-students.md).
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface names the real
 * consumer that justifies it). The consumer is {@code bootstrap.admin.AdminApplication}, which {@code @Import}s the
 * configuration explicitly, with no component scan (ADR-0024). The portal and the worker never load it. The
 * interface is this nested package and not {@code imports.web} itself, so the controller and its DTO stay internal to
 * the module and only the configuration is public.
 */
@org.springframework.modulith.NamedInterface
package com.confia.imports.web.wiring;
