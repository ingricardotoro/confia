/**
 * The shared converter between the pair of columns {@code NUMERIC(14,4)} plus currency and the {@code Money} of the
 * kernel (ADR-0015, rule 5: {@code Money} is built in one place).
 *
 * <p><b>{@code @NamedInterface} (ADR-0022), and who consumes it.</b> The consumer is {@code catalog}, whose price
 * adapter reads and writes an amount (docs/cortes/catalog-price-lists.md); every later module that stores an amount
 * consumes it too. The interface is this nested package and not {@code shared.infrastructure} itself, so the adapters of
 * the audit and of the idempotency keys stay internal to {@code shared}.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.infrastructure.money;
