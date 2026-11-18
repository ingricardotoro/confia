package com.confia.charges.domain;

import com.confia.kernel.Money;

/**
 * The extension point of scholarships and discounts (owner decision: deferred). The pipeline is {@code BasePrice ->
 * BenefitsStep -> TaxableBase -> Tax -> Total}; in this cut the step is the identity, a discount of zero. A future benefit
 * will be posted as its own {@code DISCOUNT} ledger transaction (key {@code charges:benefit:<chargeId>}) in the same database
 * transaction, so neither the key nor the shape of the posting of a charge changes.
 */
@FunctionalInterface
public interface BenefitsStep {

    /** The discount on the base price, never negative and never above the base. */
    Money discountOn(Money base);

    /** The identity: no benefits are granted. */
    BenefitsStep NONE = base -> Money.zero(base.currency());
}
