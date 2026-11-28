package com.confia.scholarships.application;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.Benefit;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Output port, read-only, of the discounts that the accrual engine applies: the active discounts that cover a payment concept and
 * whose validity includes a date, of campaign kind (they apply to everybody) or of agreement kind with an approved assignment of the
 * student in force. It never writes.
 */
public interface DiscountsInForceRepository {

    /** A discount in force with its benefit. */
    record InForce(UUID discountId, String discountCode, boolean stackable, Benefit benefit) {

        public InForce {
            Objects.requireNonNull(discountId, "discountId");
            Objects.requireNonNull(discountCode, "discountCode");
            Objects.requireNonNull(benefit, "benefit");
        }
    }

    /**
     * The discounts in force for the student on {@code on} for the concept, in any order (the service orders them).
     *
     * @param lockShare whether the rows are locked {@code FOR SHARE} until the end of the transaction
     */
    List<InForce> find(InstitutionId institution, UUID studentId, UUID paymentConceptId, LocalDate on, boolean lockShare);

    /** Whether any of the students has a discount in force on {@code on} that covers any of the concepts. */
    boolean anyCovering(InstitutionId institution, Collection<UUID> studentIds, Collection<UUID> paymentConceptIds,
            LocalDate on);
}
