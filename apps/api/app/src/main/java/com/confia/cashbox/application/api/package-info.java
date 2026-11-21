/**
 * The public API of {@code cashbox} for the modules that collect cash: {@link com.confia.cashbox.application.api.CashPayments},
 * which leaves a {@code payment_in} movement in the open session of the cashier INSIDE the database transaction of the caller
 * (docs/cortes/payments-register.md), with the plain types it takes and answers (no type of the cashbox domain crosses the
 * boundary).
 *
 * <p><b>{@code @NamedInterface("api")}, and who consumes it</b> (ADR-0022: every new named interface names the real consumer that
 * justifies it). The consumer is {@code payments}, whose cash registration needs the open session of the cashier and writes the
 * movement in the same transaction as the payment and its posting. This package is a nested one so the rest of {@code
 * cashbox.application} stays internal to the module.
 */
@org.springframework.modulith.NamedInterface("api")
package com.confia.cashbox.application.api;
