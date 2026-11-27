package com.confia.scholarships.application.api;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Output port, read-only, through which {@code charges} asks {@code scholarships} what the discounts in force offer to a student on a
 * charge (docs/cortes/scholarships-discounts.md). It answers with plain types: no type of the {@code scholarships} domain crosses the
 * boundary. Scoped to the institution given; it never writes and opens no transaction, so it runs inside the transaction of the
 * caller.
 *
 * <p>A discount is in force on a day when it is active, the day is inside its validity (both ends included) and it covers the payment
 * concept, and either it is of {@code campaign} kind (it applies to every student) or it is of {@code agreement} kind and the student
 * has an assignment that is {@code approved} (a pending, rejected or revoked one never applies) whose validity includes the day. The
 * validity of an assignment lies inside the one of its discount: the database keeps it so.
 *
 * <p>The benefit of each discount is computed here, by the rule of the discount (the share or the fixed amount, rounded {@code
 * HALF_UP} once and never above the base it is asked on), on the base the caller gives; the caller decides how they stack (the
 * cascade and the choice of the greatest), because it owns the charge. The answer comes in the order of the cascade: the percentage
 * discounts first, then the fixed amount ones, and in each group by code (owner decision of 2026-11-26).
 *
 * <p>With {@code lockShare} the rows read are locked in share mode until the end of the transaction ({@code SELECT ... FOR SHARE}), so
 * a revocation or a deactivation waits for the charge that is being written and then no longer applies to the next one. Without it
 * nothing is locked, which is what a preview, which runs in a read-only transaction, needs.
 */
public interface DiscountBenefits {

    /**
     * One discount in force: its identifier and code, whether it is stackable, whether its benefit is a percentage, and the function
     * that gives its benefit on a base (never negative, never above the base; zero for a base that is not positive).
     */
    record Applicable(UUID discountId, String discountCode, boolean stackable, boolean percentage, UnaryOperator<Money> benefit) {

        public Applicable {
            Objects.requireNonNull(discountId, "discountId");
            Objects.requireNonNull(discountCode, "discountCode");
            Objects.requireNonNull(benefit, "benefit");
        }

        /** The benefit of this discount on {@code base}. */
        public Money on(Money base) {
            return benefit.apply(Objects.requireNonNull(base, "base"));
        }
    }

    /**
     * The discounts in force on {@code on} for the student and the payment concept, in the order of the cascade. Empty when none is
     * in force, and for a base in another currency than the HNL of the discounts of this cut.
     *
     * @param currencyOfCharge the currency of the charge, so that a charge in another currency than HNL gets no discount
     */
    List<Applicable> inForce(InstitutionId institution, UUID studentId, UUID paymentConceptId, LocalDate on,
            CurrencyCode currencyOfCharge, boolean lockShare);

    /** Whether any of these students has a discount in force on {@code on} that covers any of these payment concepts. */
    boolean anyApplicable(InstitutionId institution, Collection<UUID> studentIds, Collection<UUID> paymentConceptIds,
            LocalDate on);

    /** The identity: no discount is ever in force. For a process or a test that does not exercise discounts. */
    DiscountBenefits NONE = new DiscountBenefits() {

        @Override
        public List<Applicable> inForce(InstitutionId institution, UUID studentId, UUID paymentConceptId, LocalDate on,
                CurrencyCode currencyOfCharge, boolean lockShare) {
            return List.of();
        }

        @Override
        public boolean anyApplicable(InstitutionId institution, Collection<UUID> studentIds,
                Collection<UUID> paymentConceptIds, LocalDate on) {
            return false;
        }
    };
}
