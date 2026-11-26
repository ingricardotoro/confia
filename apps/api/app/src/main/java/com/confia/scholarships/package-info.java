/**
 * The {@code scholarships} capability: the benefits that an institution gives to its students (docs/cortes/scholarships-awards.md,
 * scholarships cut 1). The administration defines scholarships (a percentage or a fixed amount, the payment concepts they cover and
 * an optional cap per period); a person requests the award of a scholarship to a student for an academic year, with a validity inside
 * it, and another person approves or rejects it (segregation of duties), and an approved award can be revoked. A student has at most
 * one approved award of a scholarship on any day. Scholarships are deactivated and awards are rejected or revoked: nothing is
 * deleted, and the decisions are insert-only. The benefit of a scholarship with approved awards does not change.
 *
 * <p>The accrual engine applies an approved award in force on the issue date of a charge, only to the charges issued afterwards
 * (INV-16); that integration, the simulator and the ledger entry of the benefit come in the second task of the cut.
 *
 * <p>Out of scope: discounts of campaign and of agreement and their accumulation rule (cut 2), prompt-payment discount, applying a
 * scholarship to charges already issued, other currencies than HNL, the notification to the guardian and the cost reports.
 */
package com.confia.scholarships;
