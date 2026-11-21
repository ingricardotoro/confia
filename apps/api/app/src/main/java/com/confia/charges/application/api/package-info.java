/**
 * The public API of {@code charges} for the modules that pay them: {@link com.confia.charges.application.api.PayableCharges}, the
 * read-only view of the receivable account and of the active charges of a student, and {@link
 * com.confia.charges.application.api.ChargePayments}, the port that {@code payments} implements to tell {@code charges} what has
 * been paid on a charge (docs/cortes/payments-register.md), with the plain types they take and answer (no type of the charges
 * domain crosses the boundary).
 *
 * <p><b>{@code @NamedInterface("api")}, and who consumes it</b> (ADR-0022: every new named interface names the real consumer that
 * justifies it). The consumer is {@code payments}, whose use cases read the active charges of a student to allocate a payment
 * and whose adapter implements {@code ChargePayments}. This package is a nested one so the rest of {@code charges.application}
 * stays internal to the module.
 */
@org.springframework.modulith.NamedInterface("api")
package com.confia.charges.application.api;
