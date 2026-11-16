/**
 * The administrative process's registration of the {@code catalog} module: {@link
 * com.confia.catalog.infrastructure.wiring.CatalogConfiguration} declares, explicitly, the jOOQ adapter of the payment
 * concepts and the use case over it (docs/cortes/catalog-payment-concepts.md).
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface names the real
 * consumer that justifies it). The consumer is {@code bootstrap.admin.AdminApplication}, which {@code @Import}s the
 * configuration explicitly, with no component scan (ADR-0024). The portal and the worker never load it. The
 * interface is this nested package and not {@code catalog.infrastructure} itself, so the adapter stays internal to the
 * module and only the configuration is public.
 */
@org.springframework.modulith.NamedInterface
package com.confia.catalog.infrastructure.wiring;
