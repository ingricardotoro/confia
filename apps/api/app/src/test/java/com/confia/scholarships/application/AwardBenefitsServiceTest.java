package com.confia.scholarships.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.scholarships.application.api.AwardBenefits;
import com.confia.scholarships.domain.Benefit;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the awards in force offer on the base of a charge: the benefit is computed by the rule of the scholarship ({@link Benefit#on}),
 * once per award, and an award offers nothing on a charge in another currency than HNL. Which awards are in force is the business of
 * the repository and is proven against the real database.
 */
class AwardBenefitsServiceTest {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID CONCEPT = UUID.randomUUID();
    private static final LocalDate ON = LocalDate.parse("2027-02-01");

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    private static AwardsInForceRepository.InForce award(String code, Benefit benefit, String cap) {
        return new AwardsInForceRepository.InForce(UUID.randomUUID(), code, benefit, cap == null ? null : hnl(cap));
    }

    private static AwardBenefitsService serviceOf(AwardsInForceRepository.InForce... awards) {
        return new AwardBenefitsService(new AwardsInForceRepository() {
            @Override
            public List<InForce> find(InstitutionId institution, UUID studentId, UUID paymentConceptId, LocalDate on,
                    boolean lockShare) {
                return List.of(awards);
            }

            @Override
            public boolean anyCovering(InstitutionId institution, Collection<UUID> studentIds,
                    Collection<UUID> paymentConceptIds, LocalDate on) {
                return awards.length > 0;
            }
        });
    }

    private static List<AwardBenefits.Offer> offers(AwardBenefitsService service, Money base) {
        return service.offers(INSTITUTION, STUDENT, CONCEPT, ON, base, award -> Money.zero(CurrencyCode.HNL), false);
    }

    private static List<AwardBenefits.Offer> offers(AwardBenefitsService service, Money base, String alreadyGiven) {
        return service.offers(INSTITUTION, STUDENT, CONCEPT, ON, base, award -> hnl(alreadyGiven), false);
    }

    @Test
    void aQuarterOfTheBaseIsTheBenefitOfThePercentageAward() {
        AwardBenefitsService service = serviceOf(award("BECA-25", Benefit.percentage("25"), null));

        assertThat(offers(service, hnl("1100.00"))).singleElement().satisfies(offer -> {
            assertThat(offer.scholarshipCode()).isEqualTo("BECA-25");
            assertThat(offer.amount()).isEqualTo(hnl("275.00"));
        });
    }

    @Test
    void aHundredPercentTakesTheWholeBase() {
        AwardBenefitsService service = serviceOf(award("TOTAL", Benefit.percentage("100"), null));

        assertThat(offers(service, hnl("1100.00"))).singleElement()
                .satisfies(offer -> assertThat(offer.amount()).isEqualTo(hnl("1100.00")));
    }

    @Test
    void aFixedAmountGreaterThanTheBaseIsLimitedToTheBase() {
        AwardBenefitsService service = serviceOf(award("FIJA", Benefit.fixed("2000.00"), null));

        assertThat(offers(service, hnl("1100.00"))).singleElement()
                .satisfies(offer -> assertThat(offer.amount()).isEqualTo(hnl("1100.00")));
    }

    @Test
    void aMidpointOfTheShareRoundsHalfUpToTheMinorUnitOnce() {
        // 12.5 % of 10.04 is exactly 1.255.
        AwardBenefitsService service = serviceOf(award("MEDIO", Benefit.percentage("12.50"), null));

        assertThat(offers(service, hnl("10.04"))).singleElement()
                .satisfies(offer -> assertThat(offer.amount()).isEqualTo(hnl("1.26")));
    }

    @Test
    void theCapPerPeriodLimitsTheBenefit() {
        AwardBenefitsService service = serviceOf(award("TOPE", Benefit.percentage("50"), "300.00"));

        assertThat(offers(service, hnl("1100.00"))).singleElement()
                .satisfies(offer -> assertThat(offer.amount()).isEqualTo(hnl("300.00")));
    }

    @Test
    void everyAwardInForceOffersItsOwnBenefitAndTheChoiceBelongsToTheCharges() {
        AwardBenefitsService service = serviceOf(award("A", Benefit.percentage("10"), null),
                award("B", Benefit.fixed("200.00"), null));

        assertThat(offers(service, hnl("1000.00"))).extracting(AwardBenefits.Offer::scholarshipCode,
                offer -> offer.amount().toString()).hasSize(2);
        assertThat(offers(service, hnl("1000.00"))).extracting(offer -> offer.amount().amount().toPlainString())
                .containsExactlyInAnyOrder("100.0000", "200.0000");
    }

    @Test
    void whatTheAwardAlreadyGaveInThePeriodIsTakenFromItsCap() {
        AwardBenefitsService service = serviceOf(award("TOPE", Benefit.percentage("50"), "600.00"));

        assertThat(offers(service, hnl("1100.00"), "0.00")).singleElement()
                .satisfies(offer -> assertThat(offer.amount()).isEqualTo(hnl("550.00")));
        assertThat(offers(service, hnl("1100.00"), "550.00")).singleElement()
                .satisfies(offer -> assertThat(offer.amount()).isEqualTo(hnl("50.00")));
        assertThat(offers(service, hnl("1100.00"), "600.00")).singleElement()
                .satisfies(offer -> assertThat(offer.amount().isZero()).isTrue());
    }

    @Test
    void anAwardWithoutACapNeverAsksWhatItAlreadyGave() {
        AwardBenefitsService service = serviceOf(award("LIBRE", Benefit.percentage("50"), null));

        List<AwardBenefits.Offer> offers = service.offers(INSTITUTION, STUDENT, CONCEPT, ON, hnl("1100.00"), award -> {
            throw new AssertionError("an award without a cap has nothing consumed");
        }, false);

        assertThat(offers).singleElement().satisfies(offer -> assertThat(offer.amount()).isEqualTo(hnl("550.00")));
    }

    @Test
    void aChargeInAnotherCurrencyThanHnlGetsNoOffer() {
        AwardBenefitsService service = serviceOf(award("A", Benefit.percentage("25"), null));

        assertThat(offers(service, Money.of("1100.00", CurrencyCode.USD))).isEmpty();
    }

    @Test
    void withoutAwardsInForceThereIsNothingToOfferAndNothingApplies() {
        AwardBenefitsService service = serviceOf();

        assertThat(offers(service, hnl("1100.00"))).isEmpty();
        assertThat(service.anyApplicable(INSTITUTION, List.of(STUDENT), List.of(CONCEPT), ON)).isFalse();
    }

    @Test
    void anAwardInForceMakesTheBenefitApplicableToThePopulation() {
        AwardBenefitsService service = serviceOf(award("A", Benefit.percentage("25"), null));

        assertThat(service.anyApplicable(INSTITUTION, List.of(STUDENT), List.of(CONCEPT), ON)).isTrue();
    }

    @Test
    void thePopulationIsAskedInChunksSoALargeSchoolNeverExceedsTheParametersOfTheDriver() {
        List<Integer> sizes = new ArrayList<>();
        AwardBenefitsService service = new AwardBenefitsService(new AwardsInForceRepository() {
            @Override
            public List<InForce> find(InstitutionId institution, UUID studentId, UUID paymentConceptId, LocalDate on,
                    boolean lockShare) {
                return List.of();
            }

            @Override
            public boolean anyCovering(InstitutionId institution, Collection<UUID> studentIds,
                    Collection<UUID> paymentConceptIds, LocalDate on) {
                sizes.add(studentIds.size());
                return false;
            }
        });
        List<UUID> students = new ArrayList<>();
        for (int i = 0; i < 2_500; i++) {
            students.add(UUID.randomUUID());
        }

        assertThat(service.anyApplicable(INSTITUTION, students, List.of(CONCEPT), ON)).isFalse();
        assertThat(sizes).containsExactly(1_000, 1_000, 500);
    }

    @Test
    void anEmptyPopulationOrNoConceptsAppliesNothing() {
        AwardBenefitsService service = serviceOf(award("A", Benefit.percentage("25"), null));

        assertThat(service.anyApplicable(INSTITUTION, List.of(), List.of(CONCEPT), ON)).isFalse();
        assertThat(service.anyApplicable(INSTITUTION, List.of(STUDENT), List.of(), ON)).isFalse();
    }

    @Test
    void theNoneOfferNothingAndApplyNothing() {
        assertThat(AwardBenefits.NONE.offers(INSTITUTION, STUDENT, CONCEPT, ON, hnl("10.00"), a -> hnl("0.00"), false)).isEmpty();
        assertThat(AwardBenefits.NONE.anyApplicable(INSTITUTION, List.of(STUDENT), List.of(CONCEPT), ON)).isFalse();
    }
}
