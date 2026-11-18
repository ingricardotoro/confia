package com.confia.charges.domain;

/** What a discrepancy of the nightly integrity check says is wrong. The names are the values stored and published. */
public enum IntegrityKind {

    /** A transaction of the ledger whose debits differ from its credits. */
    UNBALANCED_TRANSACTION,
    /** A charge without the {@code CHARGE} transaction it points at. */
    CHARGE_POSTING_MISSING,
    /** More than one {@code CHARGE} transaction for one charge. */
    CHARGE_POSTING_DUPLICATE,
    /** A transaction of a charge, or its reversal, whose total is not the total of the charge. */
    CHARGE_TOTAL_MISMATCH,
    /** A voided charge without the {@code REVERSAL} transaction its void names. */
    VOID_REVERSAL_MISSING,
    /** More than one reversal of the transaction of one charge. */
    VOID_REVERSAL_DUPLICATE,
    /** A reversal of the transaction of a charge that has no void. */
    REVERSAL_WITHOUT_VOID,
    /** A transaction of {@code charges} that no charge explains. */
    ORPHAN_POSTING,
    /** The charges do not add up to the movement of the receivable accounts of their transactions, in one currency. */
    RECEIVABLE_MISMATCH
}
