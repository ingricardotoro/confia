package com.confia.scholarships.application;

import com.confia.kernel.Money;
import com.confia.scholarships.domain.BenefitType;
import com.confia.scholarships.domain.Discount;
import com.confia.scholarships.domain.DiscountProposal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A discount as a reader needs it: its kind, benefit, validity, stackable flag, covered concepts (with code and name), state and
 * version, and whether it is in use, so the type, the value and the stackable flag of its benefit no longer change and its scope only
 * narrows. For a campaign discount it says whether its terms were approved by another person ({@code approvalStatus}) and, when a
 * proposal waits, the terms proposed ({@code pendingChange}); the terms above are always the ones in force. The amounts are exact; the
 * web layer rounds them for presentation.
 *
 * @param kind {@code campaign} or {@code agreement}
 * @param percent the percentage points with two decimals, only for a percentage benefit
 * @param fixedAmount only for a fixed amount benefit
 * @param inUse whether an assignment of the discount was approved or a charge was issued with it
 * @param approvalStatus {@code pending}, {@code approved} or {@code rejected}; an agreement discount is always approved
 * @param createdBy who created it: the one who cannot approve it while its creation is pending
 * @param pendingChange the proposal that waits for another person, or {@code null}
 */
public record DiscountView(UUID id, String code, String name, String kind, String benefitType, String percent, Money fixedAmount,
        LocalDate validFrom, LocalDate validTo, boolean stackable, List<ConceptView> concepts, boolean active, long version,
        boolean inUse, Instant createdAt, Instant updatedAt, String approvalStatus, UUID createdBy, ChangeView pendingChange) {

    public DiscountView {
        concepts = List.copyOf(concepts);
    }

    /** A payment concept that the discount covers. */
    public record ConceptView(UUID id, String code, String name) {
    }

    /** The terms that wait for approval, who proposed them and when; {@code reactivate} says the discount would also be turned on. */
    public record ChangeView(String name, String benefitType, String percent, Money fixedAmount, LocalDate validFrom,
            LocalDate validTo, boolean stackable, boolean reactivate, List<ConceptView> concepts, UUID requestedBy,
            Instant requestedAt) {

        public ChangeView {
            concepts = List.copyOf(concepts);
        }
    }

    static DiscountView of(Discount discount, List<ConceptView> concepts, List<ConceptView> proposedConcepts, boolean inUse) {
        return new DiscountView(discount.id(), discount.code(), discount.name(), discount.kind().code(),
                discount.benefit().type().name(), percentOf(discount.benefit().type(), discount.benefit().percent()),
                discount.benefit().fixedAmount(), discount.validFrom(), discount.validTo(), discount.stackable(), sorted(concepts),
                discount.active(), discount.version(), inUse, discount.createdAt(), discount.updatedAt(),
                discount.approval().code(), discount.createdBy(), change(discount.proposal(), proposedConcepts));
    }

    private static ChangeView change(DiscountProposal proposal, List<ConceptView> concepts) {
        if (proposal == null) {
            return null;
        }
        return new ChangeView(proposal.name(), proposal.benefit().type().name(),
                percentOf(proposal.benefit().type(), proposal.benefit().percent()), proposal.benefit().fixedAmount(),
                proposal.validFrom(), proposal.validTo(), proposal.stackable(), proposal.reactivate(), sorted(concepts),
                proposal.requestedBy(), proposal.requestedAt());
    }

    private static String percentOf(BenefitType type, com.confia.kernel.Percentage percent) {
        return type == BenefitType.PERCENTAGE ? percent.value().setScale(2, RoundingMode.UNNECESSARY).toPlainString() : null;
    }

    private static List<ConceptView> sorted(List<ConceptView> concepts) {
        return concepts.stream().sorted(Comparator.comparing(ConceptView::code)).toList();
    }
}
