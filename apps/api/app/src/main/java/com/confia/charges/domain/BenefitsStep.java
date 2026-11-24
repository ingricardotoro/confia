package com.confia.charges.domain;

import com.confia.kernel.Money;
import java.util.List;

/**
 * The extension point of the benefits in the pipeline of a charge: {@code BasePrice -> BenefitsStep -> TaxableBase -> Tax -> Total}.
 * Given the base the price produced, it answers what each award in force offers on it; the charge applies the greatest (see {@link
 * BenefitSelection}) and the tax is computed on what is left. The identity, {@link #NONE}, offers nothing.
 */
@FunctionalInterface
public interface BenefitsStep {

    /** What the awards in force offer on this base, in no particular order; never {@code null}. */
    List<BenefitOffer> offersOn(Money base);

    /** The identity: no benefits are offered. */
    BenefitsStep NONE = base -> List.of();
}
