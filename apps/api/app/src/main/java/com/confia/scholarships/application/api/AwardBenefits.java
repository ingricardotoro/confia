package com.confia.scholarships.application.api;

import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Output port, read-only, through which {@code charges} asks {@code scholarships} what the approved awards of a student offer on a
 * charge (docs/cortes/scholarships-awards.md, INV-16). It answers with plain types: no type of the {@code scholarships} domain crosses
 * the boundary. Scoped to the institution given; it never writes and opens no transaction, so it runs inside the transaction of the
 * caller.
 *
 * <p>An award is in force when it is {@code approved} (a pending, rejected or revoked award is never in force), the date is inside its
 * validity with both ends included, its scholarship is active and covers the payment concept. The benefit of each is computed here, by
 * the rule of the scholarship, on the base the caller gives; which of several offers applies is the decision of the caller.
 *
 * <p>The cap per period of a scholarship is shared by every charge of the award in the period (owner decision of 2026-11-24): the
 * caller, which owns the charges, says what each award already gave in the period of the charge ({@code alreadyGiven}: the non-voided
 * issued charges plus the earlier charges of the same evaluation), and the offer of an award with a cap is limited to what is left of
 * it. The function is asked only for awards whose scholarship has a cap.
 *
 * <p>With {@code lockShare} the rows read are locked in share mode until the end of the transaction ({@code SELECT ... FOR SHARE}), so
 * a revocation or a deactivation waits for the charge that is being written and then no longer applies to the next one. Without it
 * nothing is locked, which is what a preview, which runs in a read-only transaction, needs.
 */
public interface AwardBenefits {

    /** What one award in force offers on a base: the award, the code of its scholarship and the benefit. */
    record Offer(UUID awardId, String scholarshipCode, Money amount) {

        public Offer {
            Objects.requireNonNull(awardId, "awardId");
            Objects.requireNonNull(scholarshipCode, "scholarshipCode");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /**
     * What each award in force on {@code on} offers on {@code base} for the student and the payment concept, one offer per award, in
     * the order of the code of the scholarship. Empty when none is in force, and for a base in another currency than the HNL of the
     * scholarships of this cut.
     *
     * @param alreadyGiven what the award (the argument) already gave in the period that starts on {@code on}, in HNL
     */
    List<Offer> offers(InstitutionId institution, UUID studentId, UUID paymentConceptId, LocalDate on, Money base,
            Function<UUID, Money> alreadyGiven, boolean lockShare);

    /** Whether any of these students has an award in force on {@code on} that covers any of these payment concepts. */
    boolean anyApplicable(InstitutionId institution, Collection<UUID> studentIds, Collection<UUID> paymentConceptIds,
            LocalDate on);

    /** The identity: no award is ever in force. For a process or a test that does not exercise scholarships. */
    AwardBenefits NONE = new AwardBenefits() {

        @Override
        public List<Offer> offers(InstitutionId institution, UUID studentId, UUID paymentConceptId, LocalDate on,
                Money base, Function<UUID, Money> alreadyGiven, boolean lockShare) {
            return List.of();
        }

        @Override
        public boolean anyApplicable(InstitutionId institution, Collection<UUID> studentIds,
                Collection<UUID> paymentConceptIds, LocalDate on) {
            return false;
        }
    };
}
