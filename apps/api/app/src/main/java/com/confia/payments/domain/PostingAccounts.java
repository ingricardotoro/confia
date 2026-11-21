package com.confia.payments.domain;

import java.util.UUID;

/**
 * The accounts a payment is posted to: the cash and the bank accounts of the map of payments, and the receivable of the
 * charges. A payment is posted only with all of them present: an incomplete map refuses it (docs/cortes/payments-register.md).
 */
public record PostingAccounts(UUID cash, UUID bank, UUID receivable) {

    /** Whether an account of this type and student flag may be the receivable that a payment credits. */
    public static boolean acceptsReceivable(String type, boolean holdsStudentBalances) {
        return PostingPurpose.ACCOUNT_TYPE.equals(type) && holdsStudentBalances;
    }

    public boolean complete() {
        return cash != null && bank != null && receivable != null;
    }

    /** The account debited by a payment of the method; the map must be complete. */
    public UUID debitFor(PaymentMethod method) {
        requireComplete();
        return method.purpose() == PostingPurpose.CASH ? cash : bank;
    }

    /** The receivable credited with the payment; the map must be complete. */
    public UUID receivableAccount() {
        requireComplete();
        return receivable;
    }

    private void requireComplete() {
        if (!complete()) {
            throw new PostingAccountsIncompleteException();
        }
    }
}
