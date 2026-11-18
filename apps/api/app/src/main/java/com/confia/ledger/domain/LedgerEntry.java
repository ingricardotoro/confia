package com.confia.ledger.domain;

import com.confia.kernel.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * One line of a transaction: an account of the chart, the student when the account holds student balances, a side and a
 * positive amount in the minor unit of its currency (INV-03, INV-40). The ledger never rounds: a third decimal is refused.
 */
public record LedgerEntry(UUID accountId, UUID studentId, EntrySide side, Money amount) {

    public LedgerEntry {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new InvalidLedgerEntryException("an entry amount is greater than zero; the side gives the sign");
        }
        if (amount.compareTo(amount.roundToMinorUnit(java.math.RoundingMode.HALF_UP)) != 0) {
            throw new InvalidLedgerEntryException("an entry amount is in the minor unit of its currency, the ledger never rounds");
        }
    }

    LedgerEntry mirror() {
        return new LedgerEntry(accountId, studentId, side.opposite(), amount);
    }

    String canonical() {
        return accountId + "," + (studentId == null ? "-" : studentId) + "," + side + "," + amount.toPlainString() + ","
                + amount.currency();
    }
}
