package com.confia.scholarships.application;

import com.confia.kernel.Money;
import com.confia.scholarships.domain.BenefitType;
import com.confia.scholarships.domain.Scholarship;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A scholarship as a reader needs it: its benefit, cap, covered concepts (with code and name), state and version, and whether the
 * value of its benefit is locked because an award of it was approved. The amounts are exact; the web layer rounds them for
 * presentation.
 *
 * @param percent the percentage points with two decimals, only for a percentage benefit
 * @param fixedAmount only for a fixed amount benefit
 * @param periodCap {@code null} when there is no cap
 */
public record ScholarshipView(UUID id, String code, String name, String benefitType, String percent, Money fixedAmount,
        Money periodCap, List<ConceptView> concepts, boolean active, long version, boolean benefitLocked, Instant createdAt,
        Instant updatedAt) {

    public ScholarshipView {
        concepts = List.copyOf(concepts);
    }

    /** A payment concept that the scholarship covers. */
    public record ConceptView(UUID id, String code, String name) {
    }

    static ScholarshipView of(Scholarship scholarship, List<ConceptView> concepts, boolean benefitLocked) {
        boolean percentage = scholarship.benefit().type() == BenefitType.PERCENTAGE;
        String percent = percentage
                ? scholarship.benefit().percent().value().setScale(2, RoundingMode.UNNECESSARY).toPlainString() : null;
        return new ScholarshipView(scholarship.id(), scholarship.code(), scholarship.name(), scholarship.benefit().type().name(),
                percent, scholarship.benefit().fixedAmount(), scholarship.periodCap(),
                concepts.stream().sorted(Comparator.comparing(ConceptView::code)).toList(), scholarship.active(),
                scholarship.version(), benefitLocked, scholarship.createdAt(), scholarship.updatedAt());
    }
}
