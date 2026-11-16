package com.confia.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
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
 * INV-11 and the immutability of a price (docs/cortes/catalog-price-lists.md), on the pure domain: the amount rules, the
 * five shapes of overlap (exact, partial, contiguous, contained, open) and the one case the server resolves by itself.
 */
class PriceListItemTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final UUID LIST = UUID.randomUUID();
    private static final UUID CONCEPT = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static PriceListItem price(String amount, String from, String to) {
        return PriceListItem.create(UUID.randomUUID(), LIST, CONCEPT, amount, "HNL", d(from),
                to == null ? null : d(to), STAFF, NOW);
    }

    // --- The amount and the currency ---

    @Test
    void anAmountGreaterThanZeroWithUpToFourDecimalsIsKeptExactAtScaleFour() {
        PriceListItem item = price("1500", "2027-01-01", null);

        assertThat(item.price()).isEqualTo(Money.of("1500.0000", CurrencyCode.HNL));
        assertThat(item.price().toPlainString()).isEqualTo("1500.0000");
        assertThat(price("0.0001", "2027-01-01", null).price().isPositive()).isTrue();
        assertThat(price("9999999999.99", "2027-01-01", null).price().toPlainString())
                .isEqualTo("9999999999.9900");
        assertThat(price("9999999999.9949", "2027-01-01", null).price().toPlainString())
                .isEqualTo("9999999999.9949");
    }

    @Test
    void anAmountThatCannotBeRoundedToTheMinorUnitWithoutLeavingTheRangeIsRefusedInsteadOfStoredAndFailingLater() {
        assertThatThrownBy(() -> price("9999999999.9999", "2027-01-01", null))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> price("9999999999.995", "2027-01-01", null))
                .isInstanceOf(InvalidCatalogValueException.class);
    }

    @Test
    void zeroNegativeAndMoreThanFourDecimalsAreRefusedWithTheDomainErrorOfTheCatalog() {
        for (String amount : new String[] {"0", "0.0000", "-1", "-0.0001", "1.00001", "1,500.00", "1e3", " 1", "",
                "abc", "10000000000"}) {
            assertThatThrownBy(() -> price(amount, "2027-01-01", null)).as("amount '%s'", amount)
                    .isInstanceOf(InvalidCatalogValueException.class);
        }
        assertThatThrownBy(() -> price(null, "2027-01-01", null)).isInstanceOf(InvalidCatalogValueException.class);
    }

    @Test
    void aCurrencyOutsideTheEnabledSetOrWithOtherCapitalsOrMissingIsRefused() {
        for (String currency : new String[] {"EUR", "hnl", "HN", "", "HNLL"}) {
            assertThatThrownBy(() -> PriceListItem.create(UUID.randomUUID(), LIST, CONCEPT, "1.00", currency,
                    d("2027-01-01"), null, STAFF, NOW)).as("currency '%s'", currency)
                    .isInstanceOf(InvalidCatalogValueException.class);
        }
        assertThatThrownBy(() -> PriceListItem.create(UUID.randomUUID(), LIST, CONCEPT, "1.00", null,
                d("2027-01-01"), null, STAFF, NOW)).isInstanceOf(InvalidCatalogValueException.class);
        assertThat(PriceListItem.create(UUID.randomUUID(), LIST, CONCEPT, "1.00", "USD", d("2027-01-01"), null,
                STAFF, NOW).price().currency()).isEqualTo(CurrencyCode.USD);
    }

    @Test
    void aPriceBuiltDirectlyNeedsAPositiveMoneyAndAStart() {
        assertThatThrownBy(() -> new PriceListItem(UUID.randomUUID(), LIST, CONCEPT, null, d("2027-01-01"), null,
                STAFF, NOW)).isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> new PriceListItem(UUID.randomUUID(), LIST, CONCEPT, Money.zero(CurrencyCode.HNL),
                d("2027-01-01"), null, STAFF, NOW)).isInstanceOf(InvalidCatalogValueException.class);
    }

    // --- The dates ---

    @Test
    void anEndBeforeTheStartIsRefusedAndTheSameDayIsAOneDayValidity() {
        assertThatThrownBy(() -> price("1.00", "2027-01-10", "2027-01-09"))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThat(price("1.00", "2027-01-10", "2027-01-10").validTo()).isEqualTo(d("2027-01-10"));
        assertThatThrownBy(() -> PriceListItem.create(UUID.randomUUID(), LIST, CONCEPT, "1.00", "HNL", null, null,
                STAFF, NOW)).isInstanceOf(InvalidCatalogValueException.class);
    }

    // --- Overlap (INV-11) ---

    @Test
    void theFiveShapesOfOverlapAreDetectedAndAContiguousValidityIsNot() {
        PriceListItem base = price("1.00", "2027-01-01", "2027-06-30");

        assertThat(base.overlaps(price("2.00", "2027-01-01", "2027-06-30"))).as("exact").isTrue();
        assertThat(base.overlaps(price("2.00", "2027-06-01", "2027-12-31"))).as("partial, right").isTrue();
        assertThat(base.overlaps(price("2.00", "2026-06-01", "2027-01-01"))).as("partial, left").isTrue();
        assertThat(base.overlaps(price("2.00", "2027-02-01", "2027-02-28"))).as("contained").isTrue();
        assertThat(price("2.00", "2027-02-01", "2027-02-28").overlaps(base)).as("containing").isTrue();
        assertThat(base.overlaps(price("2.00", "2027-06-30", null))).as("open, touching the last day").isTrue();
        assertThat(base.overlaps(price("2.00", "2027-07-01", "2027-12-31"))).as("contiguous after").isFalse();
        assertThat(base.overlaps(price("2.00", "2026-01-01", "2026-12-31"))).as("contiguous before").isFalse();
        assertThat(base.overlaps(price("2.00", "2027-07-01", null))).as("open, after").isFalse();
        assertThat(price("2.00", "2027-07-01", null).overlaps(base)).as("open, after, symmetric").isFalse();
    }

    @Test
    void twoOpenValiditiesAlwaysOverlap() {
        assertThat(price("1.00", "2027-01-01", null).overlaps(price("2.00", "2030-01-01", null))).isTrue();
    }

    @Property
    void overlapIsSymmetricAndMatchesTheSharingOfAtLeastOneDay(@ForAll("ranges") LocalDate[] a,
            @ForAll("ranges") LocalDate[] b) {
        PriceListItem first = price("1.00", a[0].toString(), a[1] == null ? null : a[1].toString());
        PriceListItem second = price("2.00", b[0].toString(), b[1] == null ? null : b[1].toString());

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

    // --- What happens to the open validity when a new price arrives ---

    @Test
    void anOpenPriceIsClosedTheDayBeforeTheOpenPriceThatReplacesIt() {
        PriceListItem open = price("1500.00", "2027-01-01", null);

        Optional<LocalDate> lastDay = open.closingDateFor(price("1650.00", "2027-07-01", null));

        assertThat(lastDay).contains(d("2027-06-30"));
        PriceListItem closed = open.closedAt(lastDay.orElseThrow());
        assertThat(closed.validTo()).isEqualTo(d("2027-06-30"));
        assertThat(closed.price()).isEqualTo(open.price());
        assertThat(closed.validFrom()).isEqualTo(open.validFrom());
        assertThat(closed.id()).isEqualTo(open.id());
    }

    @Test
    void theDayAfterTheStartIsTheSmallestReplacement() {
        PriceListItem open = price("1.00", "2027-01-01", null);

        assertThat(open.closingDateFor(price("2.00", "2027-01-02", null))).contains(d("2027-01-01"));
    }

    @Test
    void aReplacementThatStartsTheSameDayOrBeforeTheOpenPriceIsAnOverlap() {
        PriceListItem open = price("1.00", "2027-01-01", null);

        assertThatThrownBy(() -> open.closingDateFor(price("2.00", "2027-01-01", null)))
                .isInstanceOf(PriceValidityOverlapException.class);
        assertThatThrownBy(() -> open.closingDateFor(price("2.00", "2026-12-31", null)))
                .isInstanceOf(PriceValidityOverlapException.class);
    }

    @Test
    void aBoundedPriceInsideTheOpenValidityIsAnOverlapBecauseClosingWouldLeaveDaysWithNoPrice() {
        PriceListItem open = price("1.00", "2027-01-01", null);

        assertThatThrownBy(() -> open.closingDateFor(price("2.00", "2027-07-01", "2027-07-31")))
                .isInstanceOf(PriceValidityOverlapException.class);
        assertThatThrownBy(() -> open.closingDateFor(price("2.00", "2026-06-01", "2027-01-01")))
                .isInstanceOf(PriceValidityOverlapException.class);
    }

    @Test
    void aBoundedPriceEntirelyBeforeTheOpenOneLeavesTheOpenOneAsItIs() {
        PriceListItem open = price("1.00", "2027-01-01", null);

        assertThat(open.closingDateFor(price("0.50", "2026-01-01", "2026-12-31"))).isEmpty();
    }

    @Test
    void onlyAnOpenValidityCanBeClosedAndOnlyOnOrAfterItsStart() {
        PriceListItem closed = price("1.00", "2027-01-01", "2027-03-31");
        PriceListItem open = price("1.00", "2027-01-01", null);

        assertThatThrownBy(() -> closed.closingDateFor(price("2.00", "2027-07-01", null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> closed.closedAt(d("2027-02-01"))).isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> open.closedAt(d("2026-12-31"))).isInstanceOf(InvalidCatalogValueException.class);
        assertThat(open.closedAt(d("2027-01-01")).validTo()).isEqualTo(d("2027-01-01"));
    }
}
