package com.confia.scholarships.application;

import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.scholarships.application.api.AwardBenefits;
import com.confia.scholarships.domain.Benefit;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the approved awards in force of a student offer on a charge (docs/cortes/scholarships-awards.md, INV-16). It does no
 * arithmetic of its own: the benefit of each award is {@link Benefit#on}, the one rule of the scholarship (the share or the fixed
 * amount, rounded {@code HALF_UP} once, limited to its cap per period and to the base). It reads and writes nothing else and opens no
 * transaction: it runs in the transaction of the accrual that asks.
 */
public final class AwardBenefitsService implements AwardBenefits {

    /** Students per question to the database, so a large school never exceeds the number of parameters of a statement. */
    static final int CHUNK = 1_000;

    private final AwardsInForceRepository awards;

    public AwardBenefitsService(AwardsInForceRepository awards) {
        this.awards = Objects.requireNonNull(awards, "awards");
    }

    @Override
    public List<Offer> offers(InstitutionId institution, UUID studentId, UUID paymentConceptId, LocalDate on, Money base,
            boolean lockShare) {
        Objects.requireNonNull(base, "base");
        if (base.currency() != Benefit.CURRENCY) {
            return List.of();
        }
        List<Offer> offers = new ArrayList<>();
        for (AwardsInForceRepository.InForce award : awards.find(institution, studentId, paymentConceptId, on, lockShare)) {
            offers.add(new Offer(award.awardId(), award.scholarshipCode(), award.benefit().on(base, award.periodCap())));
        }
        return List.copyOf(offers);
    }

    @Override
    public boolean anyApplicable(InstitutionId institution, Collection<UUID> studentIds, Collection<UUID> paymentConceptIds,
            LocalDate on) {
        if (studentIds.isEmpty() || paymentConceptIds.isEmpty()) {
            return false;
        }
        List<UUID> students = List.copyOf(studentIds);
        for (int from = 0; from < students.size(); from += CHUNK) {
            List<UUID> chunk = students.subList(from, Math.min(from + CHUNK, students.size()));
            if (awards.anyCovering(institution, chunk, paymentConceptIds, on)) {
                return true;
            }
        }
        return false;
    }
}
