package com.confia.scholarships.application;

import com.confia.kernel.Money;
import com.confia.scholarships.domain.BenefitType;
import com.confia.scholarships.domain.Discount;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A discount as a reader needs it: its kind, benefit, validity, stackable flag, covered concepts (with code and name), state and
 * version, and whether it is in use, so the type and the value of its benefit no longer change and its scope only narrows. The
 * amounts are exact; the web layer rounds them for presentation.
 *
 * @param kind {@code campaign} or {@code agreement}
 * @param percent the percentage points with two decimals, only for a percentage benefit
 * @param fixedAmount only for a fixed amount benefit
 * @param inUse whether an assignment of the discount was approved or a charge was issued with it
 */
public record DiscountView(UUID id, String code, String name, String kind, String benefitType, String percent, Money fixedAmount,
        LocalDate validFrom, LocalDate validTo, boolean stackable, List<ConceptView> concepts, boolean active, long version,
        boolean inUse, Instant createdAt, Instant updatedAt) {

    public DiscountView {
        concepts = List.copyOf(concepts);
    }

    /** A payment concept that the discount covers. */
    public record ConceptView(UUID id, String code, String name) {
    }

    static DiscountView of(Discount discount, List<ConceptView> concepts, boolean inUse) {
        boolean percentage = discount.benefit().type() == BenefitType.PERCENTAGE;
        String percent = percentage
                ? discount.benefit().percent().value().setScale(2, RoundingMode.UNNECESSARY).toPlainString() : null;
        return new DiscountView(discount.id(), discount.code(), discount.name(), discount.kind().code(),
                discount.benefit().type().name(), percent, discount.benefit().fixedAmount(), discount.validFrom(),
                discount.validTo(), discount.stackable(),
                concepts.stream().sorted(Comparator.comparing(ConceptView::code)).toList(), discount.active(), discount.version(),
                inUse, discount.createdAt(), discount.updatedAt());
    }
}
