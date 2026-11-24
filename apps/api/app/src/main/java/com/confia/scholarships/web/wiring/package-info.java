/**
 * The administrative process's registration of the web layer of the {@code scholarships} module: {@link
 * com.confia.scholarships.web.wiring.ScholarshipsWebConfiguration} declares, explicitly, the controllers of the scholarships and of
 * their awards (docs/cortes/scholarships-awards.md).
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface names the real consumer that
 * justifies it). The consumer is {@code bootstrap.admin.AdminApplication}, which {@code @Import}s the configuration explicitly,
 * with no component scan (ADR-0024). The portal and the worker never load it. The interface is this nested package and not
 * {@code scholarships.web} itself, so the controllers and their DTO stay internal to the module and only the configuration is public.
 */
@org.springframework.modulith.NamedInterface
package com.confia.scholarships.web.wiring;
