/**
 * The administrative process's registration of the {@code imports} module: {@link
 * com.confia.imports.infrastructure.wiring.ImportsConfiguration} declares, explicitly, the jOOQ adapter of the batches
 * and the use case of the student import (docs/cortes/imports-students.md).
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface names the real
 * consumer that justifies it). The consumer is {@code bootstrap.admin.AdminApplication}, which {@code @Import}s the
 * configuration explicitly, with no component scan (ADR-0024). The portal and the worker never load it. The
 * interface is this nested package and not {@code imports.infrastructure} itself, so the adapter stays internal to the
 * module and only the configuration is public.
 */
@org.springframework.modulith.NamedInterface
package com.confia.imports.infrastructure.wiring;
