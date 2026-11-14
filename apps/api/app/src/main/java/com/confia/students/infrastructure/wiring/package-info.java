/**
 * The administrative process's registration of the {@code students} module: {@link
 * com.confia.students.infrastructure.wiring.StudentsConfiguration} declares, explicitly, the jOOQ adapter,
 * the protector of the national identity document, the blind-index service and the use case of the
 * registry (docs/cortes/students-registry.md).
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface names the
 * real consumer that justifies it). The consumer is {@code bootstrap.admin.AdminApplication}, which
 * {@code @Import}s the configuration explicitly, with no component scan (ADR-0024). The portal and the
 * worker never load it. The interface is this nested package and not {@code students.infrastructure}
 * itself, so the adapters stay internal to the module and only the configuration is public.
 */
@org.springframework.modulith.NamedInterface
package com.confia.students.infrastructure.wiring;
