/**
 * The {@code cashbox} capability: the cash sessions of the cashiers (docs/cortes/cashbox-sessions.md, F4 cut 1). A cashier opens
 * its session with a float, records deposits, withdrawals and adjustments, and closes it declaring the physical count; the server
 * computes the expected cash and the difference, and a difference needs a reason and the approval of someone other than the
 * cashier (INV-21, INV-22, INV-23). Movements, closes and approvals are insert-only; the status of the session only moves
 * forward, with the record that justifies each move, and the database refuses everything else.
 *
 * <p>Out of scope: payments and the {@code payment_in} movement (cut 2), refunds, the count by denomination, physical drawers
 * and the accounting entries of the cash. This cut does not touch the ledger.
 */
package com.confia.cashbox;
