package com.confia.charges.domain;

/**
 * Why one charge of an accrual run was not created (docs/cortes/charges-accrual.md, engine, points 3, 5 and 7). Each one is
 * an error of the CHARGE, recorded in the items of the run and never a failure of the run: the configuration is fixed and
 * the run is executed again, which creates only what is missing. Never a silent default (no price is never zero, a missing
 * tax rate is never 0 %).
 */
public enum AccrualErrorCode {
    /** The most specific price level that has a price of the concept has none in force at the pricing date. */
    NO_PRICE,
    /** Two price lists of the same level cover the pricing date: neither is chosen. */
    AMBIGUOUS_PRICE,
    /** The concept is taxable and has no tax rate assigned. */
    TAX_RATE_MISSING,
    /** The concept is taxable and its tax rate is inactive. */
    TAX_RATE_INACTIVE,
    /** The concept is taxable and its tax rate has no validity covering the pricing date. */
    TAX_RATE_NOT_IN_FORCE,
    /** The payment concept is inactive. */
    CONCEPT_INACTIVE,
    /** The student is inactive. */
    STUDENT_INACTIVE,
    /** The sub-account of the student is under review and refuses writes. */
    ACCOUNT_UNDER_REVIEW,
    /** Another automatic charge of the student and the concept covers part of the period but not the same period. */
    OVERLAPPING_CHARGE,
    /** The database refused the unit of the student (ledger or charge write); the student was rolled back whole. */
    POSTING_FAILED
}
