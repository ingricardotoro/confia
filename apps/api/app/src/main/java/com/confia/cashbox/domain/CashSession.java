package com.confia.cashbox.domain;

import com.confia.kernel.Money;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The cash session of a cashier (INV-21, INV-22, INV-23). All the arithmetic of the cash lives here, on {@link Money}: the
 * expected amount is the opening float plus the movements, computed by the server and only shown by the browser. A session never
 * holds a balance: the expected amount is derived from its float and its movements every time.
 *
 * <p>The status advances only along open to closed or pending approval, and pending approval to closed, and each step comes
 * with an insert-only record (the close, the approval).
 *
 * @param requestKey the Idempotency-Key that opened it, permanent, with the hash of what it asked
 */
public record CashSession(UUID id, UUID cashierId, Money openingFloat, CashSessionStatus status, Instant openedAt,
        String requestKey, String requestHash) {

    public static final int REASON_MIN = 20;
    public static final int REASON_MAX = 500;
    public static final int APPROVAL_NOTE_MAX = 500;

    public CashSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cashierId, "cashierId");
        Objects.requireNonNull(openingFloat, "openingFloat");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(openedAt, "openedAt");
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(requestHash, "requestHash");
    }

    /** Opens a session with a float of zero or more, in the currency of the institution. */
    public static CashSession open(UUID id, UUID cashierId, Money openingFloat, Instant now, String requestKey,
            String requestHash) {
        if (openingFloat.isNegative() || openingFloat.currency() != CashAmounts.CURRENCY) {
            throw new InvalidCashboxValueException();
        }
        return new CashSession(id, cashierId, openingFloat, CashSessionStatus.OPEN, now, requestKey, requestHash);
    }

    public boolean answers(String hash) {
        return requestHash.equals(hash);
    }

    public boolean isOpen() {
        return status == CashSessionStatus.OPEN;
    }

    /** The float plus the deposits, minus the withdrawals, plus the signed adjustments (the formula of INV-23). */
    public Money expected(List<CashMovement> movements) {
        Money expected = openingFloat;
        for (CashMovement movement : movements) {
            expected = expected.add(movement.effect());
        }
        return expected;
    }

    /**
     * A new movement. The session must be open, the amount valid for its type and the expected cash must not end below zero
     * (a withdrawal larger than what the session holds is refused).
     *
     * @param notes required for an adjustment (20 to 500 characters); optional up to 500 for the others
     */
    public CashMovement recordMovement(UUID movementId, MovementType type, Money amount, String notes,
            List<CashMovement> existing, UUID actor, Instant now, String movementKey, String movementHash) {
        requireOpen();
        if (amount.currency() != openingFloat.currency()) {
            throw new InvalidCashboxValueException();
        }
        boolean validAmount = type == MovementType.ADJUSTMENT ? !amount.isZero() : amount.isPositive();
        if (!validAmount) {
            throw new InvalidCashboxValueException();
        }
        String text = null;
        if (type == MovementType.ADJUSTMENT) {
            text = FreeText.of(notes, CashMovement.ADJUSTMENT_NOTES_MIN, CashMovement.NOTES_MAX);
        } else if (notes != null && !notes.isBlank()) {
            text = FreeText.of(notes, 1, CashMovement.NOTES_MAX);
        }
        if (expected(existing).add(type.effect(amount)).isNegative()) {
            throw new CashExpectedNegativeException();
        }
        return new CashMovement(movementId, id, type, amount, text, movementKey, movementHash, actor, now);
    }

    /**
     * The close with the declared count. A difference (declared minus expected) other than zero needs a reason of 20 to 500
     * characters; with no difference the reason is not kept.
     */
    public CashClose close(UUID closeId, Money declared, String reason, List<CashMovement> movements, UUID actor,
            Instant now, String closeKey, String closeHash) {
        requireOpen();
        if (declared.currency() != openingFloat.currency() || declared.isNegative()) {
            throw new InvalidCashboxValueException();
        }
        Money expected = expected(movements);
        String text = null;
        if (!declared.equals(expected)) {
            if (reason == null || reason.isBlank()) {
                throw new CashDifferenceReasonRequiredException();
            }
            text = FreeText.of(reason, REASON_MIN, REASON_MAX);
        }
        return new CashClose(closeId, id, declared, expected, text, actor, now, closeKey, closeHash);
    }

    /** The session after its close: pending approval with a difference, closed without one. */
    public CashSession afterClose(CashClose close) {
        return new CashSession(id, cashierId, openingFloat, close.resultingStatus(), openedAt, requestKey, requestHash);
    }

    /** The approval of the close by someone other than the cashier, of a session that is pending approval. */
    public CashApproval approve(UUID approvalId, UUID approver, String note, Instant now, String approvalKey,
            String approvalHash) {
        if (cashierId.equals(approver)) {
            throw new CashApprovalBySameCashierException();
        }
        if (status != CashSessionStatus.PENDING_APPROVAL) {
            throw new CashSessionNotAwaitingApprovalException();
        }
        String text = FreeText.of(note, 1, APPROVAL_NOTE_MAX);
        return new CashApproval(approvalId, id, approver, text, now, approvalKey, approvalHash);
    }

    /** The session once approved. */
    public CashSession afterApproval() {
        return new CashSession(id, cashierId, openingFloat, CashSessionStatus.CLOSED, openedAt, requestKey, requestHash);
    }

    private void requireOpen() {
        if (!isOpen()) {
            throw new CashSessionNotOpenException();
        }
    }
}
