package com.confia.charges.domain;

import com.confia.kernel.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One price of a payment concept in an ACTIVE price list of the academic year, as the engine reads it: the item, the level
 * of its list ({@link PriceScope}) and the grade or modality the list belongs to, the amount and the validity (the last day
 * is {@code null} while it is open).
 */
public record PriceCandidate(UUID priceListItemId, PriceScope scope, UUID scopeRefId, Money amount, LocalDate validFrom,
        LocalDate validTo) {

    public PriceCandidate {
        Objects.requireNonNull(priceListItemId, "priceListItemId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(validFrom, "validFrom");
        if ((scope == PriceScope.INSTITUTION) != (scopeRefId == null)) {
            throw new InvalidChargesValueException();
        }
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new InvalidChargesValueException();
        }
    }

    /** Whether the validity covers the date, both ends included. */
    public boolean covers(LocalDate date) {
        return !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
    }
}
