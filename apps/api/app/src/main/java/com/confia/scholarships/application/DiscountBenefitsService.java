package com.confia.scholarships.application;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.scholarships.application.api.DiscountBenefits;
import com.confia.scholarships.domain.Benefit;
import com.confia.scholarships.domain.BenefitType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the discounts in force offer to a student on a charge (docs/cortes/scholarships-discounts.md). It does no arithmetic of its
 * own: the benefit of each discount is {@link Benefit#on}, the one rule of the benefit (the share or the fixed amount, rounded {@code
 * HALF_UP} once and limited to the base). It orders what it finds for the cascade: the percentage discounts first, then the fixed
 * amount ones, and in each group by code and then by identifier, so the order never depends on how the rows were read. It reads and
 * writes nothing else and opens no transaction: it runs in the transaction of the accrual that asks.
 */
public final class DiscountBenefitsService implements DiscountBenefits {

    private static final Comparator<DiscountsInForceRepository.InForce> CASCADE_ORDER = Comparator
            .<DiscountsInForceRepository.InForce, Boolean>comparing(found -> found.benefit().type() != BenefitType.PERCENTAGE)
            .thenComparing(DiscountsInForceRepository.InForce::discountCode)
            .thenComparing(DiscountsInForceRepository.InForce::discountId);

    private final DiscountsInForceRepository discounts;

    public DiscountBenefitsService(DiscountsInForceRepository discounts) {
        this.discounts = Objects.requireNonNull(discounts, "discounts");
    }

    @Override
    public List<Applicable> inForce(InstitutionId institution, UUID studentId, UUID paymentConceptId, LocalDate on,
            CurrencyCode currencyOfCharge, boolean lockShare) {
        Objects.requireNonNull(currencyOfCharge, "currencyOfCharge");
        if (currencyOfCharge != Benefit.CURRENCY) {
            return List.of();
        }
        return discounts.find(institution, studentId, paymentConceptId, on, lockShare).stream().sorted(CASCADE_ORDER)
                .map(found -> new Applicable(found.discountId(), found.discountCode(), found.stackable(),
                        found.benefit().type() == BenefitType.PERCENTAGE, base -> found.benefit().on(base, null)))
                .toList();
    }

    @Override
    public boolean anyApplicable(InstitutionId institution, Collection<UUID> studentIds, Collection<UUID> paymentConceptIds,
            LocalDate on) {
        if (studentIds.isEmpty() || paymentConceptIds.isEmpty()) {
            return false;
        }
        List<UUID> students = List.copyOf(studentIds);
        for (int from = 0; from < students.size(); from += AwardBenefitsService.CHUNK) {
            List<UUID> chunk = students.subList(from, Math.min(from + AwardBenefitsService.CHUNK, students.size()));
            if (discounts.anyCovering(institution, chunk, paymentConceptIds, on)) {
                return true;
            }
        }
        return false;
    }
}
