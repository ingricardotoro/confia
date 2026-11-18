package com.confia.charges.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Price by date: the most specific level with any price decides, a gap is NO_PRICE and a tie is AMBIGUOUS_PRICE. */
class PriceResolverTest {

    private static final UUID GRADE = UUID.randomUUID();
    private static final UUID MODALITY = UUID.randomUUID();
    private static final LocalDate FEBRUARY = LocalDate.parse("2027-02-01");

    private static PriceCandidate price(PriceScope scope, UUID ref, String amount, String from, String to) {
        return new PriceCandidate(UUID.randomUUID(), scope, ref, Money.of(amount, CurrencyCode.HNL),
                LocalDate.parse(from), to == null ? null : LocalDate.parse(to));
    }

    private static PriceResolver.ResolvedPrice resolve(List<PriceCandidate> candidates) {
        return PriceResolver.resolve(candidates, GRADE, MODALITY, FEBRUARY);
    }

    private static void assertFails(List<PriceCandidate> candidates, AccrualErrorCode code) {
        assertThatThrownBy(() -> resolve(candidates)).isInstanceOf(AccrualFailure.class)
                .extracting(e -> ((AccrualFailure) e).errorCode()).isEqualTo(code);
    }

    @Test
    void noPriceListAtAllIsNoPrice() {
        assertFails(List.of(), AccrualErrorCode.NO_PRICE);
    }

    @Test
    void onlyTheInstitutionListStillPrices() {
        PriceCandidate general = price(PriceScope.INSTITUTION, null, "1500.00", "2027-01-01", null);

        assertThat(resolve(List.of(general)).priceListItemId()).isEqualTo(general.priceListItemId());
        assertThat(resolve(List.of(general)).amount()).isEqualTo(Money.of("1500.00", CurrencyCode.HNL));
    }

    @Test
    void theGradeListWinsOverTheModalityAndTheInstitutionList() {
        PriceCandidate general = price(PriceScope.INSTITUTION, null, "1500.00", "2027-01-01", null);
        PriceCandidate modality = price(PriceScope.MODALITY, MODALITY, "1400.00", "2027-01-01", null);
        PriceCandidate grade = price(PriceScope.GRADE, GRADE, "1300.00", "2027-01-01", null);

        assertThat(resolve(List.of(general, modality, grade)).priceListItemId()).isEqualTo(grade.priceListItemId());
        assertThat(resolve(List.of(general, modality)).priceListItemId()).isEqualTo(modality.priceListItemId());
    }

    @Test
    void aListOfAnotherGradeOrModalityDoesNotApply() {
        PriceCandidate otherGrade = price(PriceScope.GRADE, UUID.randomUUID(), "900.00", "2027-01-01", null);
        PriceCandidate general = price(PriceScope.INSTITUTION, null, "1500.00", "2027-01-01", null);

        assertThat(resolve(List.of(otherGrade, general)).priceListItemId()).isEqualTo(general.priceListItemId());
    }

    @Test
    void theMoreSpecificLevelWithoutValidityAtTheDateIsNoPriceAndNeverFallsToTheGeneralOne() {
        PriceCandidate gradeEnded = price(PriceScope.GRADE, GRADE, "1300.00", "2026-01-01", "2027-01-31");
        PriceCandidate general = price(PriceScope.INSTITUTION, null, "1500.00", "2027-01-01", null);

        assertFails(List.of(gradeEnded, general), AccrualErrorCode.NO_PRICE);
    }

    @Test
    void aValidityThatEndedTheDayBeforeThePeriodDoesNotCoverIt() {
        assertFails(List.of(price(PriceScope.INSTITUTION, null, "1500.00", "2026-01-01", "2027-01-31")),
                AccrualErrorCode.NO_PRICE);
    }

    @Test
    void aValidityThatStartsOrEndsExactlyOnThePricingDateCoversIt() {
        assertThat(resolve(List.of(price(PriceScope.INSTITUTION, null, "1500.00", "2027-02-01", null))).amount())
                .isEqualTo(Money.of("1500.00", CurrencyCode.HNL));
        assertThat(resolve(List.of(price(PriceScope.INSTITUTION, null, "1500.00", "2026-01-01", "2027-02-01"))).amount())
                .isEqualTo(Money.of("1500.00", CurrencyCode.HNL));
    }

    @Test
    void aPriceChangedInTheMiddleOfTheYearEachPeriodTakesItsOwnValidity() {
        PriceCandidate first = price(PriceScope.INSTITUTION, null, "1500.00", "2027-01-01", "2027-05-31");
        PriceCandidate second = price(PriceScope.INSTITUTION, null, "1650.00", "2027-06-01", null);
        List<PriceCandidate> all = List.of(first, second);

        assertThat(PriceResolver.resolve(all, GRADE, MODALITY, LocalDate.parse("2027-05-01")).priceListItemId())
                .isEqualTo(first.priceListItemId());
        assertThat(PriceResolver.resolve(all, GRADE, MODALITY, LocalDate.parse("2027-06-01")).priceListItemId())
                .isEqualTo(second.priceListItemId());
    }

    @Test
    void twoListsOfTheSameLevelCoveringTheDateAreAmbiguousAndNeverChosen() {
        assertFails(List.of(price(PriceScope.INSTITUTION, null, "1500.00", "2027-01-01", null),
                price(PriceScope.INSTITUTION, null, "1600.00", "2027-01-01", null)), AccrualErrorCode.AMBIGUOUS_PRICE);
    }

    @Test
    void overlappingListsOfTheSameLevelThatDoNotBothCoverTheDateAreNotAmbiguous() {
        PriceCandidate current = price(PriceScope.INSTITUTION, null, "1500.00", "2027-01-01", null);
        PriceCandidate past = price(PriceScope.INSTITUTION, null, "1400.00", "2026-01-01", "2026-12-31");

        assertThat(resolve(List.of(past, current)).priceListItemId()).isEqualTo(current.priceListItemId());
    }

    @Test
    void aCandidateRejectsAnInconsistentLevelOrValidity() {
        assertThatThrownBy(() -> price(PriceScope.GRADE, null, "1.00", "2027-01-01", null))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> price(PriceScope.INSTITUTION, UUID.randomUUID(), "1.00", "2027-01-01", null))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> price(PriceScope.INSTITUTION, null, "1.00", "2027-02-01", "2027-01-01"))
                .isInstanceOf(InvalidChargesValueException.class);
    }
}
