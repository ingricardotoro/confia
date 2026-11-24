package com.confia.charges.domain;

import com.confia.kernel.CurrencyCode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which of the benefits offered to one charge is applied (docs/cortes/scholarships-awards.md, more than one scholarship on the same
 * charge): only the one with the greatest benefit, never the sum of them (the accumulation rule comes in the second cut). On a tie
 * the scholarship with the lowest code wins, and when even the code is the same the lowest award identifier does, so the choice never
 * depends on the order the offers were read in. An offer of zero is no benefit, and an offer in another currency than the charge is
 * not applicable (the scholarships of this cut are in HNL).
 */
public final class BenefitSelection {

    private static final Comparator<BenefitOffer> PREFERENCE = Comparator
            .comparing(BenefitOffer::amount, Comparator.reverseOrder())
            .thenComparing(BenefitOffer::scholarshipCode)
            .thenComparing(BenefitOffer::awardId);

    private BenefitSelection() {
    }

    /** The offer to apply to a charge in {@code currency}, or empty when none gives anything. */
    public static Optional<BenefitOffer> best(List<BenefitOffer> offers, CurrencyCode currency) {
        return offers.stream().filter(offer -> offer.amount().currency() == currency)
                .filter(offer -> offer.amount().isPositive()).min(PREFERENCE);
    }
}
