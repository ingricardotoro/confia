package com.confia.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * INV-11 and the immutability of a period (docs/cortes/catalog-tax-rates.md), on the pure domain: the percentage rules,
 * the five shapes of overlap (exact, partial, contiguous, contained, open) and the one case the server resolves by
 * itself. The percentages are inputs of the cases, never values the system knows.
 */
class TaxRatePeriodTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final UUID RATE = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static TaxRatePeriod period(String percent, String from, String to) {
        return TaxRatePeriod.create(UUID.randomUUID(), RATE, percent, d(from), to == null ? null : d(to), STAFF, NOW);
    }

    // --- The percentage ---

    @Test
    void aPercentageIsKeptExactAtScaleFour() {
        TaxRatePeriod created = period("7.5", "2027-01-01", null);

        assertThat(created.ratePercent().toPlainString()).isEqualTo("7.5000");
        assertThat(created.taxRateId()).isEqualTo(RATE);
        assertThat(created.createdBy()).isEqualTo(STAFF);
    }

    @Test
    void aPercentageThatIsZeroNegativeAboveOneHundredOrWithMoreThanFourDecimalsIsRefused() {
        for (String bad : new String[] {"0", "-1", "100.0001", "101", "1.00001", "abc", ""}) {
            assertThatThrownBy(() -> period(bad, "2027-01-01", null)).as(bad)
                    .isInstanceOf(InvalidCatalogValueException.class);
        }
        assertThatThrownBy(() -> new TaxRatePeriod(UUID.randomUUID(), RATE, null, d("2027-01-01"), null, STAFF, NOW))
                .isInstanceOf(InvalidCatalogValueException.class);
    }

    // --- The dates ---

    @Test
    void anEndBeforeTheStartIsRefusedAndTheSameDayIsAOneDayValidity() {
        assertThatThrownBy(() -> period("5", "2027-01-10", "2027-01-09"))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThat(period("5", "2027-01-10", "2027-01-10").validTo()).isEqualTo(d("2027-01-10"));
        assertThatThrownBy(() -> TaxRatePeriod.create(UUID.randomUUID(), RATE, "5", null, null, STAFF, NOW))
                .isInstanceOf(InvalidCatalogValueException.class);
    }

    @Test
    void aDateOutsideTwoThousandToTwentyOneHundredIsRefusedAndTheBoundsAreAccepted() {
        assertThatThrownBy(() -> period("5", "1999-12-31", null)).isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> period("5", "2101-01-01", null)).isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> period("5", "2027-01-01", "2101-01-01"))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> period("5", "0202-03-01", null)).isInstanceOf(InvalidCatalogValueException.class);
        assertThat(period("5", "2000-01-01", "2100-12-31").validTo()).isEqualTo(d("2100-12-31"));
    }

    // --- Overlap (INV-11) ---

    @Test
    void theFiveShapesOfOverlapAreDetectedAndAContiguousValidityIsNot() {
        TaxRatePeriod base = period("5", "2027-01-01", "2027-06-30");

        assertThat(base.overlaps(period("6", "2027-01-01", "2027-06-30"))).as("exact").isTrue();
        assertThat(base.overlaps(period("6", "2027-06-01", "2027-12-31"))).as("partial, right").isTrue();
        assertThat(base.overlaps(period("6", "2026-06-01", "2027-01-01"))).as("partial, left").isTrue();
        assertThat(base.overlaps(period("6", "2027-02-01", "2027-02-28"))).as("contained").isTrue();
        assertThat(period("6", "2027-02-01", "2027-02-28").overlaps(base)).as("containing").isTrue();
        assertThat(base.overlaps(period("6", "2027-06-30", null))).as("open, touching the last day").isTrue();
        assertThat(base.overlaps(period("6", "2027-07-01", "2027-12-31"))).as("contiguous after").isFalse();
        assertThat(base.overlaps(period("6", "2026-01-01", "2026-12-31"))).as("contiguous before").isFalse();
        assertThat(base.overlaps(period("6", "2027-07-01", null))).as("open, after").isFalse();
        assertThat(period("6", "2027-07-01", null).overlaps(base)).as("open, after, symmetric").isFalse();
    }

    @Test
    void twoOpenValiditiesAlwaysOverlap() {
        assertThat(period("5", "2027-01-01", null).overlaps(period("6", "2030-01-01", null))).isTrue();
    }

    @Property
    void overlapIsSymmetricAndMatchesTheSharingOfAtLeastOneDay(@ForAll("ranges") LocalDate[] a,
            @ForAll("ranges") LocalDate[] b) {
        TaxRatePeriod first = period("5", a[0].toString(), a[1] == null ? null : a[1].toString());
        TaxRatePeriod second = period("6", b[0].toString(), b[1] == null ? null : b[1].toString());

        boolean shareADay = false;
        LocalDate horizon = d("2030-12-31");
        for (LocalDate day = d("2026-12-01"); !day.isAfter(horizon) && !shareADay; day = day.plusDays(1)) {
            shareADay = covers(a, day) && covers(b, day);
        }

        assertThat(first.overlaps(second)).isEqualTo(second.overlaps(first));
        assertThat(first.overlaps(second)).isEqualTo(shareADay);
    }

    private static boolean covers(LocalDate[] range, LocalDate day) {
        return !day.isBefore(range[0]) && (range[1] == null || !day.isAfter(range[1]));
    }

    @Provide
    Arbitrary<LocalDate[]> ranges() {
        Arbitrary<LocalDate> starts = Arbitraries.integers().between(0, 400)
                .map(offset -> d("2027-01-01").plusDays(offset));
        return starts.flatMap(start -> Arbitraries.integers().between(-1, 300)
                .map(length -> new LocalDate[] {start, length < 0 ? null : start.plusDays(length)}));
    }

    // --- What happens to the open period when a new one arrives ---

    @Test
    void anOpenPeriodIsClosedTheDayBeforeTheOpenPeriodThatReplacesIt() {
        TaxRatePeriod open = period("5", "2027-01-01", null);

        Optional<LocalDate> lastDay = open.closingDateFor(period("6", "2027-07-01", null));

        assertThat(lastDay).contains(d("2027-06-30"));
        TaxRatePeriod closed = open.closedAt(lastDay.orElseThrow());
        assertThat(closed.validTo()).isEqualTo(d("2027-06-30"));
        assertThat(closed.ratePercent()).isEqualTo(open.ratePercent());
        assertThat(closed.validFrom()).isEqualTo(open.validFrom());
        assertThat(closed.id()).isEqualTo(open.id());
    }

    @Test
    void theDayAfterTheStartIsTheSmallestReplacement() {
        TaxRatePeriod open = period("5", "2027-01-01", null);

        assertThat(open.closingDateFor(period("6", "2027-01-02", null))).contains(d("2027-01-01"));
    }

    @Test
    void aReplacementThatStartsTheSameDayOrBeforeTheOpenPeriodIsAnOverlap() {
        TaxRatePeriod open = period("5", "2027-01-01", null);

        assertThatThrownBy(() -> open.closingDateFor(period("6", "2027-01-01", null)))
                .isInstanceOf(TaxRatePeriodOverlapException.class);
        assertThatThrownBy(() -> open.closingDateFor(period("6", "2026-12-31", null)))
                .isInstanceOf(TaxRatePeriodOverlapException.class);
    }

    @Test
    void aBoundedPeriodInsideTheOpenValidityIsAnOverlapBecauseClosingWouldLeaveDaysWithNoRate() {
        TaxRatePeriod open = period("5", "2027-01-01", null);

        assertThatThrownBy(() -> open.closingDateFor(period("6", "2027-07-01", "2027-07-31")))
                .isInstanceOf(TaxRatePeriodOverlapException.class);
        assertThatThrownBy(() -> open.closingDateFor(period("6", "2026-06-01", "2027-01-01")))
                .isInstanceOf(TaxRatePeriodOverlapException.class);
    }

    @Test
    void aBoundedPeriodEntirelyBeforeTheOpenOneLeavesTheOpenOneAsItIs() {
        TaxRatePeriod open = period("5", "2027-01-01", null);

        assertThat(open.closingDateFor(period("4", "2026-01-01", "2026-12-31"))).isEmpty();
    }

    @Test
    void onlyAnOpenValidityCanBeClosedAndOnlyOnOrAfterItsStart() {
        TaxRatePeriod closed = period("5", "2027-01-01", "2027-03-31");
        TaxRatePeriod open = period("5", "2027-01-01", null);

        assertThatThrownBy(() -> closed.closingDateFor(period("6", "2027-07-01", null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> closed.closedAt(d("2027-02-01"))).isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> open.closedAt(d("2026-12-31"))).isInstanceOf(InvalidCatalogValueException.class);
        assertThat(open.closedAt(d("2027-01-01")).validTo()).isEqualTo(d("2027-01-01"));
    }
}
