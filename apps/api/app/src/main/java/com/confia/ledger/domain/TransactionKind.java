package com.confia.ledger.domain;

/** The closed set of transaction kinds (confia-ledger-invariants, section 3). */
public enum TransactionKind {
    CHARGE, DISCOUNT, PAYMENT, LATE_FEE, CREDIT_NOTE, REVERSAL, ADJUSTMENT, WRITE_OFF
}
