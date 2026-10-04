/**
 * The administrative process's production wiring of the {@code shared} module: {@link
 * com.confia.shared.platform.infrastructure.SharedPlatformConfiguration} declares, explicitly,
 * every bean the shared adapters need (web-edge-foundations design.md, decisions 1 and 2).
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface of
 * {@code shared} names the real consumer that justifies it). The consumer is {@code
 * bootstrap.admin.AdminApplication}, which {@code @Import}s the configuration explicitly, with no
 * component scan (ADR-0024). The portal and the worker never load it.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.platform.infrastructure;
