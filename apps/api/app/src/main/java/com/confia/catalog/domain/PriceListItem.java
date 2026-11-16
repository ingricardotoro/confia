package com.confia.catalog.domain;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InvalidMoneyAmountException;
import com.confia.kernel.Money;
import com.confia.kernel.UnsupportedCurrencyException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The price of one payment concept in one price list for a validity: from {@code validFrom} to {@code validTo}, both
 * inclusive, or with no end while {@code validTo} is {@code null}. A price is never edited (INV-12, anticipated): the
 * amount, the currency, the concept and the start never change, and the one thing that ever happens to it is that its
 * open validity is closed, once, by the arrival of the price that replaces it ({@link #closedAt}).
 *
 * <p>Two prices of the same concept in the same list never overlap in time (INV-11). The database refuses the overlap,
 * and {@link #closingDateFor} decides the one case the server resolves by itself.
 */
public record PriceListItem(UUID id, UUID priceListId, UUID paymentConceptId, Money price, LocalDate validFrom,
        LocalDate validTo, UUID createdBy, Instant createdAt) {

    /** The first day a validity may start or end on. */
    public static final LocalDate EARLIEST_DAY = LocalDate.of(2000, 1, 1);

    /** The last day a validity may start or end on. */
    public static final LocalDate LATEST_DAY = LocalDate.of(2100, 12, 31);

    public PriceListItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(priceListId, "priceListId");
        Objects.requireNonNull(paymentConceptId, "paymentConceptId");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        if (price == null || !price.isPositive() || validFrom == null
                || outOfBounds(validFrom) || (validTo != null && (outOfBounds(validTo) || validTo.isBefore(validFrom)))) {
            throw new InvalidCatalogValueException();
        }
    }

    /**
     * A new price, read from the text of the API. The amount is greater than zero and has at most the decimals of the minor unit of its currency (two), the
     * currency is one the system operates with, and the end, when there is one, is not before the start.
     */
    public static PriceListItem create(UUID id, UUID priceListId, UUID paymentConceptId, String amount,
            String currency, LocalDate validFrom, LocalDate validTo, UUID createdBy, Instant now) {
        return new PriceListItem(id, priceListId, paymentConceptId, priceOf(amount, currency), validFrom, validTo,
                createdBy, now);
    }

    private static boolean outOfBounds(LocalDate day) {
        return day.isBefore(EARLIEST_DAY) || day.isAfter(LATEST_DAY);
    }

    static Money priceOf(String amount, String currency) {
        try {
            Money price = Money.of(amount, CurrencyCode.fromIsoCode(currency));
            // A price has at most the decimals of the minor unit of its currency, so what is stored is what is shown.
            if (price.amount().stripTrailingZeros().scale() > price.currency().minorUnitDigits()) {
                throw new InvalidCatalogValueException();
            }
            return price;
        } catch (InvalidMoneyAmountException | UnsupportedCurrencyException | NullPointerException e) {
            throw new InvalidCatalogValueException();
        }
    }

    public boolean isOpen() {
        return validTo == null;
    }

    /** Whether the two validities share at least one day (the ends are inclusive, an open end is unbounded). */
    public boolean overlaps(PriceListItem other) {
        boolean thisStartsBeforeOtherEnds = other.validTo == null || !validFrom.isAfter(other.validTo);
        boolean otherStartsBeforeThisEnds = validTo == null || !other.validFrom.isAfter(validTo);
        return thisStartsBeforeOtherEnds && otherStartsBeforeThisEnds;
    }

    /**
     * What happens to this price, the open one of its concept, when {@code next} arrives (INV-11):
     *
     * <ul>
     *   <li>they do not overlap: nothing, the new price is simply added (for example a history entered after the fact);
     *   <li>the new price has no end and starts after this one: this one is closed the day before, so the new price is
     *       its replacement and no day is left with two prices nor, between them, with none;
     *   <li>any other overlap (same start, an earlier start, a new price with an end inside this validity): refused,
     *       because closing this price would either leave days with no price or hide a mistake.
     * </ul>
     *
     * @return the last day of this price, or empty when it stays as it is
     * @throws PriceValidityOverlapException in the refused case
     */
    public Optional<LocalDate> closingDateFor(PriceListItem next) {
        if (!isOpen()) {
            throw new IllegalStateException("only an open validity is closed");
        }
        if (!overlaps(next)) {
            return Optional.empty();
        }
        if (next.isOpen() && next.validFrom.isAfter(validFrom)) {
            return Optional.of(next.validFrom.minusDays(1));
        }
        throw new PriceValidityOverlapException();
    }

    /** The same price with its validity closed on {@code lastDay}; nothing else changes. */
    public PriceListItem closedAt(LocalDate lastDay) {
        if (!isOpen() || lastDay == null || lastDay.isBefore(validFrom)) {
            throw new InvalidCatalogValueException();
        }
        return new PriceListItem(id, priceListId, paymentConceptId, price, validFrom, lastDay, createdBy,
                createdAt);
    }
}
