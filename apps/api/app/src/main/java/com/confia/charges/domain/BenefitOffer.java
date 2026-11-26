package com.confia.charges.domain;

import com.confia.kernel.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * What an award offers to one charge: the award, the code of its scholarship and the benefit it gives on the base of that charge.
 * The scholarships capability computes the amount (it owns the rule: the share or the fixed amount, limited to its cap per period
 * and to the base); this module only chooses among the offers and applies the one it chooses. Nothing here is a scholarship: no
 * type of the {@code scholarships} domain crosses the boundary.
 */
public record BenefitOffer(UUID awardId, String scholarshipCode, Money amount) {

    public BenefitOffer {
        Objects.requireNonNull(awardId, "awardId");
        Objects.requireNonNull(scholarshipCode, "scholarshipCode");
        Objects.requireNonNull(amount, "amount");
        if (scholarshipCode.isBlank() || amount.isNegative()) {
            throw new InvalidChargesValueException();
        }
    }
}
