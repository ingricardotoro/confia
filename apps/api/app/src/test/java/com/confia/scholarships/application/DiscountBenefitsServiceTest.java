package com.confia.scholarships.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.scholarships.application.api.DiscountBenefits;
import com.confia.scholarships.domain.Benefit;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the discounts in force offer on a charge (docs/cortes/scholarships-discounts.md), over a fake port: the order of the cascade
 * (percentage discounts first, then fixed amount ones, each group by code), the benefit of each on the base it is asked on, the
 * rounding, the currency and the chunks of students.
 */
class DiscountBenefitsServiceTest {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID CONCEPT = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.parse("2027-03-01");

    /** A fake port that answers what it was given and counts the questions about students. */
    private static final class Fake implements DiscountsInForceRepository {

        final List<InForce> found = new ArrayList<>();
        final List<Integer> chunks = new ArrayList<>();
        boolean covering;

        @Override
        public List<InForce> find(InstitutionId institution, UUID studentId, UUID paymentConceptId, LocalDate on,
                boolean lockShare) {
            return List.copyOf(found);
        }

        @Override
        public boolean anyCovering(InstitutionId institution, Collection<UUID> studentIds, Collection<UUID> paymentConceptIds,
                LocalDate on) {
            chunks.add(studentIds.size());
            return covering;
        }
    }

    private static DiscountsInForceRepository.InForce percent(String code, String points, boolean stackable) {
        return new DiscountsInForceRepository.InForce(UUID.randomUUID(), code, stackable, Benefit.percentage(points));
    }

    private static DiscountsInForceRepository.InForce fixed(String code, String amount) {
        return new DiscountsInForceRepository.InForce(UUID.randomUUID(), code, true, Benefit.fixed(amount));
    }

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    private static List<DiscountBenefits.Applicable> inForce(Fake fake) {
        return new DiscountBenefitsService(fake).inForce(INSTITUTION, STUDENT, CONCEPT, DAY, CurrencyCode.HNL, false);
    }

    @Test
    void thePercentageDiscountsGoFirstThenTheFixedAmountOnesAndEachGroupByCode() {
        Fake fake = new Fake();
        fake.found.add(fixed("A-FIJO", "50.00"));
        fake.found.add(percent("Z-PCT", "10", true));
        fake.found.add(fixed("B-FIJO", "20.00"));
        fake.found.add(percent("M-PCT", "5", true));

        assertThat(inForce(fake)).extracting(DiscountBenefits.Applicable::discountCode)
                .containsExactly("M-PCT", "Z-PCT", "A-FIJO", "B-FIJO");
        assertThat(inForce(fake)).extracting(DiscountBenefits.Applicable::percentage).containsExactly(true, true, false, false);
    }

    @Test
    void theSameCodeIsOrderedByIdentifierSoTheOrderNeverDependsOnTheRowOrder() {
        Fake fake = new Fake();
        DiscountsInForceRepository.InForce first = percent("SAME", "10", true);
        DiscountsInForceRepository.InForce second = percent("SAME", "20", true);
        fake.found.add(first);
        fake.found.add(second);
        List<UUID> expected = new ArrayList<>(List.of(first.discountId(), second.discountId()));
        expected.sort(UUID::compareTo);

        assertThat(inForce(fake)).extracting(DiscountBenefits.Applicable::discountId).containsExactlyElementsOf(expected);
    }

    @Test
    void theBenefitIsComputedOnTheBaseItIsAskedOnSoACascadeCanChainThem() {
        Fake fake = new Fake();
        fake.found.add(percent("PCT", "10", true));

        DiscountBenefits.Applicable discount = inForce(fake).get(0);

        assertThat(discount.on(hnl("1000.00"))).isEqualTo(hnl("100.00"));
        assertThat(discount.on(hnl("800.00"))).isEqualTo(hnl("80.00"));
        assertThat(discount.stackable()).isTrue();
    }

    @Test
    void thePercentageIsRoundedHalfUpToTheMinorUnit() {
        Fake fake = new Fake();
        fake.found.add(percent("PCT", "10", true));

        DiscountBenefits.Applicable discount = inForce(fake).get(0);

        assertThat(discount.on(hnl("0.05"))).isEqualTo(hnl("0.01"));
        assertThat(discount.on(hnl("0.04"))).isEqualTo(hnl("0.00"));
        assertThat(discount.on(hnl("100.05"))).isEqualTo(hnl("10.01"));
    }

    @Test
    void aFixedAmountNeverExceedsTheBaseAndABaseThatIsNotPositiveGetsZero() {
        Fake fake = new Fake();
        fake.found.add(fixed("FIJO", "300.00"));

        DiscountBenefits.Applicable discount = inForce(fake).get(0);

        assertThat(discount.on(hnl("1000.00"))).isEqualTo(hnl("300.00"));
        assertThat(discount.on(hnl("120.00"))).isEqualTo(hnl("120.00"));
        assertThat(discount.on(hnl("0.00"))).isEqualTo(hnl("0.00"));
        assertThat(discount.on(hnl("-5.00"))).isEqualTo(hnl("0.00"));
        assertThat(discount.percentage()).isFalse();
    }

    @Test
    void aFullPercentageTakesTheWholeBaseAndNoMore() {
        Fake fake = new Fake();
        fake.found.add(percent("TODO", "100", false));

        DiscountBenefits.Applicable discount = inForce(fake).get(0);

        assertThat(discount.on(hnl("720.00"))).isEqualTo(hnl("720.00"));
        assertThat(discount.stackable()).isFalse();
    }

    @Test
    void aChargeInAnotherCurrencyThanHnlGetsNoDiscount() {
        Fake fake = new Fake();
        fake.found.add(percent("PCT", "10", true));

        assertThat(new DiscountBenefitsService(fake).inForce(INSTITUTION, STUDENT, CONCEPT, DAY, CurrencyCode.USD, false)).isEmpty();
    }

    @Test
    void nothingInForceMeansNoDiscountAndTheIdentityNeverFindsAny() {
        assertThat(inForce(new Fake())).isEmpty();
        assertThat(DiscountBenefits.NONE.inForce(INSTITUTION, STUDENT, CONCEPT, DAY, CurrencyCode.HNL, true)).isEmpty();
        assertThat(DiscountBenefits.NONE.anyApplicable(INSTITUTION, List.of(STUDENT), List.of(CONCEPT), DAY)).isFalse();
    }

    @Test
    void theStudentsAreAskedInChunksAndTheFirstChunkThatCoversEndsTheSearch() {
        Fake fake = new Fake();
        List<UUID> students = new ArrayList<>();
        for (int i = 0; i < 2_500; i++) {
            students.add(UUID.randomUUID());
        }

        assertThat(new DiscountBenefitsService(fake).anyApplicable(INSTITUTION, students, List.of(CONCEPT), DAY)).isFalse();
        assertThat(fake.chunks).containsExactly(1_000, 1_000, 500);

        Fake covering = new Fake();
        covering.covering = true;
        assertThat(new DiscountBenefitsService(covering).anyApplicable(INSTITUTION, students, List.of(CONCEPT), DAY)).isTrue();
        assertThat(covering.chunks).containsExactly(1_000);
    }

    @Test
    void noStudentsOrNoConceptsMeansNothingApplicableWithoutAskingTheDatabase() {
        Fake fake = new Fake();
        DiscountBenefitsService service = new DiscountBenefitsService(fake);

        assertThat(service.anyApplicable(INSTITUTION, List.of(), List.of(CONCEPT), DAY)).isFalse();
        assertThat(service.anyApplicable(INSTITUTION, List.of(STUDENT), List.of(), DAY)).isFalse();
        assertThat(fake.chunks).isEmpty();
    }
}
