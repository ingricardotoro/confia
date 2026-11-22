/**
 * The {@code payments} capability: the money that students hand over (docs/cortes/payments-register.md, F4 cut 2). A cashier
 * registers a payment in cash, which is born confirmed, posted to the ledger and allocated to the open charges of the student, oldest
 * first, and leaves a {@code payment_in} movement in the open session of the cashier; or a declared bank transfer, which touches no
 * balance until a person authorized, who is not the one who registered it, confirms it (and then it is posted and allocated) or
 * rejects it (and then it leaves no entry). What is left after every pending charge is covered stays credited in the receivable of
 * the student (INV-17 to INV-20, INV-24). Payments, decisions and allocations are insert-only; the status of a payment only moves
 * forward, with the decision that justifies it, and what is outstanding in a charge is derived, never stored.
 *
 * <p>Out of scope: card and gateways, allocation chosen by hand, the printable receipt, the reversal of a confirmed payment, credit
 * balances applied to future debt or refunded, bank reconciliation and fiscal invoices.
 */
package com.confia.payments;
