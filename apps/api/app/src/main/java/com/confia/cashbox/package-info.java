/**
 * The {@code cashbox} capability: the cash sessions of the cashiers (docs/cortes/cashbox-sessions.md, F4 cut 1). A cashier opens
 * its session with a float, records deposits, withdrawals and adjustments, and closes it declaring the physical count; the server
 * computes the expected cash and the difference, and a difference needs a reason and the approval of someone other than the
 * cashier (INV-21, INV-22, INV-23). Movements, closes and approvals are insert-only; the status of the session only moves
 * forward, with the record that justifies each move, and the database refuses everything else.
 *
 * <p>Cut 2 of F4 (docs/cortes/payments-register.md) adds the {@code payment_in} movement, which only a cash payment of {@code payments}
 * writes, through the published API {@code CashPayments}, in the transaction of the payment; it adds cash like a deposit and the
 * formula of the expected amount does not change.
 *
 * <p>Out of scope: refunds, the count by denomination, physical drawers and the accounting entries of the cash (the posting of the
 * cash is made by {@code payments}, not by this module).
 */
package com.confia.cashbox;
