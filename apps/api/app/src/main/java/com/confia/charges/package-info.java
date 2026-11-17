/**
 * The {@code charges} capability: what an institution accrues, how often and to whom. Its first cut is the charge plans
 * (docs/cortes/charges-plans.md): a plan of an academic year that targets one modality, grade or section, with a
 * frequency, a due day and one item per payment concept (periods and first day), with the use case {@code
 * ManageChargePlans}, its jOOQ adapters and its six routes under {@code /api/v1/charges/plans}. A plan is born as a draft,
 * is activated once, which freezes it, and is archived once; it is never deleted nor reactivated. A plan carries no price:
 * the accrual engine (F3, cut 3) resolves it by date in the price lists of {@code catalog}.
 *
 * <p>Out of scope: generating charges, applying prices, scholarships and discounts, and writing in the ledger (cut 3);
 * manual charges and adjustments (cut 4). The payment concept, the academic year and the target of a plan are proven
 * through the directories that {@code catalog} and {@code organization} publish, never by importing their domain nor reading
 * their tables. Every write leaves an entry in the audit trail.
 */
package com.confia.charges;
