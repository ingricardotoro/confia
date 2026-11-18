package com.confia.ledger.domain;

/** The side of an entry. The amount is always positive and the side gives its sign: debit raises the debt. */
public enum EntrySide {
    DEBIT, CREDIT;

    public EntrySide opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
