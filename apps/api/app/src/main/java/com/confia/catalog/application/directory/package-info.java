/**
 * The read-only view of the payment concepts that {@code catalog} publishes to the other modules: {@link
 * com.confia.catalog.application.directory.PaymentConceptDirectory} and the {@link
 * com.confia.catalog.application.directory.PaymentConceptRef} it answers, which only say whether a concept exists in an
 * institution and whether it is active. Nothing else of the catalog is public.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface names the real consumer
 * that justifies it). The consumer is {@code charges}, whose plan items name a payment concept that must exist in the
 * institution of the session and be active when the item is added, without importing the domain of {@code catalog} nor
 * reading its tables (docs/cortes/charges-plans.md). The interface is this nested package and not {@code
 * catalog.application} itself, so the use cases and the repositories of the catalog stay internal to the module.
 */
@org.springframework.modulith.NamedInterface
package com.confia.catalog.application.directory;
