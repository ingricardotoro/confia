package com.confia.catalog.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The percentage of a tax rate for a validity: from {@code validFrom} to {@code validTo}, both inclusive, or with no end
 * while {@code validTo} is {@code null}. A period is never edited: the percentage, the rate and the start never change,
 * and the one thing that ever happens to it is that its open validity is closed, once, by the arrival of the period that
 * replaces it ({@link #closedAt}).
 *
 * <p>Two periods of the same rate never overlap in time (INV-11). The database refuses the overlap, and {@link
 * #closingDateFor} decides the one case the server resolves by itself.
 */
public record TaxRatePeriod(UUID id, UUID taxRateId, RatePercent ratePercent, LocalDate validFrom, LocalDate validTo,
        UUID createdBy, Instant createdAt) {

    public TaxRatePeriod {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taxRateId, "taxRateId");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        if (ratePercent == null || validFrom == null || outOfBounds(validFrom)
                || (validTo != null && (outOfBounds(validTo) || validTo.isBefore(validFrom)))) {
            throw new InvalidCatalogValueException();
        }
    }

    /** A new period, read from the text of the API. */
    public static TaxRatePeriod create(UUID id, UUID taxRateId, String ratePercent, LocalDate validFrom,
            LocalDate validTo, UUID createdBy, Instant now) {
        return new TaxRatePeriod(id, taxRateId, RatePercent.of(ratePercent), validFrom, validTo, createdBy, now);
    }

    private static boolean outOfBounds(LocalDate day) {
        return day.isBefore(PriceListItem.EARLIEST_DAY) || day.isAfter(PriceListItem.LATEST_DAY);
    }

    public boolean isOpen() {
        return validTo == null;
    }

    /** Whether the two validities share at least one day (the ends are inclusive, an open end is unbounded). */
    public boolean overlaps(TaxRatePeriod other) {
        boolean thisStartsBeforeOtherEnds = other.validTo == null || !validFrom.isAfter(other.validTo);
        boolean otherStartsBeforeThisEnds = validTo == null || !other.validFrom.isAfter(validTo);
        return thisStartsBeforeOtherEnds && otherStartsBeforeThisEnds;
    }

    /**
     * What happens to this period, the open one of its rate, when {@code next} arrives (INV-11), by the same rules as the
     * prices of a list:
     *
     * <ul>
     *   <li>they do not overlap: nothing, the new period is simply added (for example a history entered after the fact);
     *   <li>the new period has no end and starts after this one: this one is closed the day before;
     *   <li>any other overlap: refused.
     * </ul>
     *
     * @return the last day of this period, or empty when it stays as it is
     * @throws TaxRatePeriodOverlapException in the refused case
     */
    public Optional<LocalDate> closingDateFor(TaxRatePeriod next) {
        if (!isOpen()) {
            throw new IllegalStateException("only an open validity is closed");
        }
        if (!overlaps(next)) {
            return Optional.empty();
        }
        if (next.isOpen() && next.validFrom.isAfter(validFrom)) {
            return Optional.of(next.validFrom.minusDays(1));
        }
        throw new TaxRatePeriodOverlapException();
    }

    /** The same period with its validity closed on {@code lastDay}; nothing else changes. */
    public TaxRatePeriod closedAt(LocalDate lastDay) {
        if (!isOpen() || lastDay == null || lastDay.isBefore(validFrom)) {
            throw new InvalidCatalogValueException();
        }
        return new TaxRatePeriod(id, taxRateId, ratePercent, validFrom, lastDay, createdBy, createdAt);
    }
}
