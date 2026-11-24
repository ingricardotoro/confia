package com.confia.scholarships.application;

import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.scholarships.domain.Benefit;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Output port, read-only, of the awards that the accrual engine applies: the approved awards of a student in force on a date, of an
 * active scholarship that covers a payment concept. It never writes.
 */
public interface AwardsInForceRepository {

    /** An award in force with the benefit and the cap of its scholarship. */
    record InForce(UUID awardId, String scholarshipCode, Benefit benefit, Money periodCap) {

        public InForce {
            Objects.requireNonNull(awardId, "awardId");
            Objects.requireNonNull(scholarshipCode, "scholarshipCode");
            Objects.requireNonNull(benefit, "benefit");
        }
    }

    /**
     * The awards of the student in force on {@code on} for the concept, ordered by the code of the scholarship and the award.
     *
     * @param lockShare whether the rows are locked {@code FOR SHARE} until the end of the transaction
     */
    List<InForce> find(InstitutionId institution, UUID studentId, UUID paymentConceptId, LocalDate on, boolean lockShare);

    /** Whether any of the students has an award in force on {@code on} that covers any of the concepts. */
    boolean anyCovering(InstitutionId institution, Collection<UUID> studentIds, Collection<UUID> paymentConceptIds,
            LocalDate on);
}
