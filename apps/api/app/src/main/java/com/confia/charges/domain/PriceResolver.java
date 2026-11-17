package com.confia.charges.domain;

import com.confia.kernel.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Price by date (docs/cortes/charges-accrual.md, engine point 3). The prices of a concept live in price lists of three
 * levels, a grade over a modality over the institution. The MOST SPECIFIC level that has any price of the concept, in any
 * validity, decides: if that level has no validity covering the date the answer is {@link AccrualErrorCode#NO_PRICE}, never
 * a silent fall to a more general level; if two lists of that level cover the date it is {@link
 * AccrualErrorCode#AMBIGUOUS_PRICE}, never a choice. Pure and deterministic.
 */
public final class PriceResolver {

    private PriceResolver() {
    }

    /** The price that determined a charge: the item that holds it and its amount, which is the base of the charge. */
    public record ResolvedPrice(UUID priceListItemId, Money amount) {
    }

    /**
     * @param candidates the prices of one concept in the active price lists of the academic year
     * @param gradeId the grade of the student
     * @param modalityId the modality of the grade of the student
     * @throws AccrualFailure {@code NO_PRICE} or {@code AMBIGUOUS_PRICE}
     */
    public static ResolvedPrice resolve(List<PriceCandidate> candidates, UUID gradeId, UUID modalityId,
            LocalDate pricingDate) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(pricingDate, "pricingDate");
        for (PriceScope level : PriceScope.values()) {
            List<PriceCandidate> ofLevel = candidates.stream().filter(c -> appliesTo(c, level, gradeId, modalityId))
                    .toList();
            if (ofLevel.isEmpty()) {
                continue;
            }
            List<PriceCandidate> inForce = ofLevel.stream().filter(c -> c.covers(pricingDate)).toList();
            if (inForce.isEmpty()) {
                throw new AccrualFailure(AccrualErrorCode.NO_PRICE);
            }
            if (inForce.size() > 1) {
                throw new AccrualFailure(AccrualErrorCode.AMBIGUOUS_PRICE);
            }
            PriceCandidate price = inForce.get(0);
            return new ResolvedPrice(price.priceListItemId(), price.amount());
        }
        throw new AccrualFailure(AccrualErrorCode.NO_PRICE);
    }

    private static boolean appliesTo(PriceCandidate candidate, PriceScope level, UUID gradeId, UUID modalityId) {
        if (candidate.scope() != level) {
            return false;
        }
        return switch (level) {
            case GRADE -> candidate.scopeRefId().equals(gradeId);
            case MODALITY -> candidate.scopeRefId().equals(modalityId);
            case INSTITUTION -> true;
        };
    }
}
