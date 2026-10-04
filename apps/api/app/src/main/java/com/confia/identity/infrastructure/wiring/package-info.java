/**
 * The administrative process's registration of the {@code identity} module: {@link
 * com.confia.identity.infrastructure.wiring.IdentityConfiguration} declares, explicitly, the use
 * cases and adapters of staff authentication (web-edge-foundations design.md, decisions 1 and 3).
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface
 * names the real consumer that justifies it). The consumer is {@code
 * bootstrap.admin.AdminApplication}, which {@code @Import}s the configuration explicitly, with no
 * component scan (ADR-0024). The portal and the worker never load it. The interface is this
 * nested package and not {@code identity.infrastructure} itself, so the adapters stay internal to
 * the module and only the configuration is public.
 *
 * <p>The package sits under {@code infrastructure} because the configuration's factory methods
 * take a {@code DSLContext} and ADR-0015 rule 4 confines {@code org.jooq} to that layer.
 */
@org.springframework.modulith.NamedInterface
package com.confia.identity.infrastructure.wiring;
