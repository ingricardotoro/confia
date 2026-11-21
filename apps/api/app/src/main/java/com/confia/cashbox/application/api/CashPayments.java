package com.confia.cashbox.application.api;

import com.confia.kernel.Money;
import com.confia.shared.security.AuthenticatedActor;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What {@code payments} asks of {@code cashbox} when a cashier collects cash (docs/cortes/payments-register.md; INV-20): to leave a
 * {@code payment_in} movement in the OPEN session of that cashier, inside the database transaction the caller already opened with
 * the transactional component. It opens no transaction, applies no security context and retries nothing; it refuses to run
 * without an active transaction. The session is locked {@code FOR UPDATE}, so the movement and a concurrent close serialize, and
 * the expected cash of the session already sums the movement without changing the formula of the close.
 */
public interface CashPayments {

    /**
     * Records the movement {@code payment_in} of {@code amount} in the open session of the cashier of {@code actor}. The cashier
     * is the actor, never a member of a request: another cashier's session is never touched.
     *
     * @param requestId the request, for the audit entry
     * @param paymentId the payment that claims the movement; it makes the movement idempotent
     * @return the session and the movement, or empty when the cashier has no open session
     * @throws IllegalStateException when no transaction is active
     */
    Optional<PaymentIn> recordPaymentIn(AuthenticatedActor actor, UUID requestId, UUID paymentId, Money amount);

    /** The movement a cash payment left and the session it left it in. */
    record PaymentIn(UUID sessionId, UUID movementId) {

        public PaymentIn {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(movementId, "movementId");
        }
    }
}
