package com.confia.charges.application;

import com.confia.catalog.application.directory.PricingDirectory;
import com.confia.charges.domain.AccrualErrorCode;
import com.confia.charges.domain.AccrualFailure;
import com.confia.charges.domain.AccrualPeriod;
import com.confia.charges.domain.AccrualPeriods;
import com.confia.charges.domain.BenefitOffer;
import com.confia.charges.domain.ChargeEvaluator;
import com.confia.charges.domain.ChargePlan;
import com.confia.charges.domain.ComputedCharge;
import com.confia.charges.domain.ConceptTerms;
import com.confia.charges.domain.PriceCandidate;
import com.confia.charges.domain.PriceScope;
import com.confia.charges.domain.StudentPlacement;
import com.confia.charges.domain.TaxRateInfo;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.organization.application.directory.PlacementDirectory;
import com.confia.scholarships.application.api.AwardBenefits;
import com.confia.students.application.directory.EnrollmentRoster;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.students.application.directory.StudentRef;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The reads and the rules shared by the preview and the execution of an accrual (docs/cortes/charges-accrual.md, engine): who
 * the population of a period is, and what each student and concept of the plan would produce. It does arithmetic nowhere:
 * every amount comes from the pure rules of {@code charges.domain}. It opens no transaction, so the caller decides whether
 * the reads lock ({@code lockShare}) and in which transaction they run.
 *
 * <p>The benefit of a scholarship enters the pipeline of each charge as a step ({@link com.confia.charges.domain.BenefitsStep}):
 * {@code scholarships} says what each approved award in force at the first day of the period offers on the base the price
 * produced, and the pure rules of {@code charges.domain} choose the greatest and compute the tax on what is left. The preview, the
 * run of the worker and the simulator all come through here, so they cannot disagree.
 */
final class AccrualEngine {

    /** One student and concept of a period and what the engine found for it. */
    record Line(UUID studentId, UUID paymentConceptId, AccrualOutcome outcome, AccrualErrorCode errorCode,
            ComputedCharge charge, UUID existingChargeId) {

        static Line created(ComputedCharge charge) {
            return new Line(charge.studentId(), charge.paymentConceptId(), AccrualOutcome.CREATED, null, charge, null);
        }

        static Line existing(UUID studentId, UUID conceptId, AccrualOutcome outcome, UUID existingChargeId) {
            return new Line(studentId, conceptId, outcome, null, null, existingChargeId);
        }

        static Line error(UUID studentId, UUID conceptId, AccrualErrorCode code) {
            return new Line(studentId, conceptId, AccrualOutcome.ERROR, code, null, null);
        }
    }

    private final PlacementDirectory placements;
    private final EnrollmentRoster roster;
    private final StudentDirectory students;
    private final PricingDirectory pricing;
    private final ChargeRepository charges;
    private final AwardBenefits awards;

    AccrualEngine(PlacementDirectory placements, EnrollmentRoster roster, StudentDirectory students,
            PricingDirectory pricing, ChargeRepository charges, AwardBenefits awards) {
        this.placements = Objects.requireNonNull(placements, "placements");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.students = Objects.requireNonNull(students, "students");
        this.pricing = Objects.requireNonNull(pricing, "pricing");
        this.charges = Objects.requireNonNull(charges, "charges");
        this.awards = Objects.requireNonNull(awards, "awards");
    }

    /**
     * The students of the period: the roster of the plan target in the academic year, with the grade and the modality of
     * the section each one was in at the reference date. Empty (absent) when the target is a section that does not belong
     * to the academic year of the plan.
     */
    Optional<List<StudentPlacement>> population(InstitutionId institution, ChargePlan plan, AccrualPeriod period) {
        PlacementDirectory.TargetScope scope = PlacementDirectory.TargetScope.valueOf(plan.targetScope().name());
        Optional<List<UUID>> sections = placements.sectionsOfTarget(institution, plan.academicYearId(), scope,
                plan.targetRefId());
        if (sections.isEmpty()) {
            return Optional.empty();
        }
        List<EnrollmentRoster.RosterEntry> entries = roster.enrolledDuring(institution, plan.academicYearId(),
                sections.get(), period.start(), period.end());
        Map<UUID, PlacementDirectory.SectionPlacement> places = placements.placementsOf(institution,
                entries.stream().map(EnrollmentRoster.RosterEntry::sectionId).distinct().toList());
        Map<UUID, StudentRef> refs = students.findAll(institution,
                entries.stream().map(EnrollmentRoster.RosterEntry::studentId).toList());
        List<StudentPlacement> population = new ArrayList<>();
        for (EnrollmentRoster.RosterEntry entry : entries) {
            PlacementDirectory.SectionPlacement place = places.get(entry.sectionId());
            if (place == null) {
                continue;
            }
            StudentRef ref = refs.get(entry.studentId());
            population.add(new StudentPlacement(entry.studentId(), entry.enrollmentId(), place.gradeId(),
                    place.modalityId(), ref != null && ref.active()));
        }
        population.sort(Comparator.comparing(StudentPlacement::studentId));
        return Optional.of(List.copyOf(population));
    }

    /**
     * What each concept of the plan that starts at the period produces for the student: a charge to create, the charge that
     * already exists (found without any lock, because a committed charge never changes) or the error of the charge. The
     * existing charge is looked up first, so a student already charged is never reported as a price error after the price
     * list changed. Lines come in the order of the concepts.
     *
     * <p>The cap per period of a scholarship is shared by the charges of the award in the period: what the award already gave in
     * charges that exist (not voided) is read from the charges, and what it gives to the charges of this very evaluation, which are
     * not written yet, is added as each one is computed. The concepts are evaluated in the order they are written in (by identifier),
     * so the database, which checks each charge as it is inserted, finds the same remainder the engine used.
     *
     * @param lockShare whether the price and the tax rows are locked {@code FOR SHARE} until the end of the transaction
     */
    List<Line> evaluateStudent(InstitutionId institution, ChargePlan plan, StudentPlacement student,
            List<AccrualPeriods.PlanPeriod> periods, boolean lockShare) {
        List<Line> lines = new ArrayList<>();
        Map<UUID, Money> given = new HashMap<>();
        List<AccrualPeriods.PlanPeriod> ordered = periods.stream()
                .sorted(Comparator.comparing(AccrualPeriods.PlanPeriod::paymentConceptId)).toList();
        for (AccrualPeriods.PlanPeriod planPeriod : ordered) {
            UUID conceptId = planPeriod.paymentConceptId();
            AccrualPeriod period = planPeriod.period();
            Optional<Line> already = alreadyAccrued(institution, plan.id(), student.studentId(), conceptId, period,
                    charges.findOverlapping(institution, student.studentId(), conceptId, period.start(), period.end()));
            if (already.isPresent()) {
                lines.add(already.get());
                continue;
            }
            try {
                ComputedCharge charge = compute(institution, plan, student, planPeriod, given, lockShare);
                if (charge.benefit() != null) {
                    given.merge(charge.benefit().awardId(), charge.benefit().amount(), Money::add);
                }
                lines.add(Line.created(charge));
            } catch (AccrualFailure failure) {
                lines.add(Line.error(student.studentId(), conceptId, failure.errorCode()));
            }
        }
        lines.sort(Comparator.comparing(Line::paymentConceptId));
        return lines;
    }

    /**
     * The line that an automatic charge already there produces: found with the same period it is {@code ALREADY_ACCRUED}
     * (or {@code ALREADY_ACCRUED_OTHER_PLAN} when another plan made it), and with another period that overlaps it is the
     * error {@code OVERLAPPING_CHARGE}. Empty when nothing overlaps.
     */
    static Optional<Line> alreadyAccrued(InstitutionId institution, UUID planId, UUID studentId, UUID conceptId,
            AccrualPeriod period, List<ChargeRepository.ExistingCharge> existing) {
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        for (ChargeRepository.ExistingCharge charge : existing) {
            if (charge.samePeriodAs(period.start(), period.end())) {
                AccrualOutcome outcome = charge.planId() != null && charge.planId().equals(planId)
                        ? AccrualOutcome.ALREADY_ACCRUED : AccrualOutcome.ALREADY_ACCRUED_OTHER_PLAN;
                return Optional.of(Line.existing(studentId, conceptId, outcome, charge.id()));
            }
        }
        return Optional.of(Line.error(studentId, conceptId, AccrualErrorCode.OVERLAPPING_CHARGE));
    }

    private ComputedCharge compute(InstitutionId institution, ChargePlan plan, StudentPlacement student,
            AccrualPeriods.PlanPeriod planPeriod, Map<UUID, Money> given, boolean lockShare) {
        UUID conceptId = planPeriod.paymentConceptId();
        AccrualPeriod period = planPeriod.period();
        PricingDirectory.ConceptTerms terms = pricing.concept(institution, conceptId, lockShare)
                .orElseThrow(() -> new AccrualFailure(AccrualErrorCode.CONCEPT_INACTIVE));
        ConceptTerms concept = new ConceptTerms(terms.conceptId(), terms.active(), terms.category(), terms.taxable(),
                terms.taxRateId());
        List<PriceCandidate> prices = pricing.prices(institution, plan.academicYearId(), conceptId, lockShare).stream()
                .map(price -> new PriceCandidate(price.priceListItemId(), PriceScope.valueOf(price.scope()),
                        price.scopeRefId(), price.amount(), price.validFrom(), price.validTo()))
                .toList();
        TaxRateInfo rate = null;
        if (concept.taxable() && concept.taxRateId() != null) {
            rate = pricing.taxRate(institution, concept.taxRateId(), lockShare).map(AccrualEngine::toInfo).orElse(null);
        }
        ChargeEvaluator evaluator = new ChargeEvaluator(base -> awards
                .offers(institution, student.studentId(), conceptId, period.start(), base,
                        awardId -> alreadyGiven(institution, awardId, period.start(), given), lockShare).stream()
                .map(offer -> new BenefitOffer(offer.awardId(), offer.scholarshipCode(), offer.amount())).toList());
        return evaluator.evaluate(student, plan.id(), planPeriod, concept, prices, rate);
    }

    /** What the award gave in the period: the charges that exist plus the earlier ones of this evaluation. */
    private Money alreadyGiven(InstitutionId institution, UUID awardId, LocalDate periodStart, Map<UUID, Money> given) {
        Money issued = charges.benefitGiven(institution, awardId, periodStart);
        Money pending = given.get(awardId);
        return pending == null ? issued : issued.add(pending);
    }

    private static TaxRateInfo toInfo(PricingDirectory.TaxRateTerms terms) {
        return new TaxRateInfo(terms.taxRateId(), terms.active(), terms.validities().stream()
                .map(v -> new TaxRateInfo.Validity(v.periodId(), v.percent(), v.validFrom(), v.validTo())).toList());
    }

    /** The period to evaluate: the first of the periods that start at the date. */
    static AccrualPeriod periodOf(List<AccrualPeriods.PlanPeriod> startingAt, LocalDate periodStart) {
        return startingAt.stream().map(AccrualPeriods.PlanPeriod::period).filter(p -> p.start().equals(periodStart))
                .findFirst().orElseThrow();
    }
}
