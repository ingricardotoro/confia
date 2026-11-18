package com.confia.charges.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One payment concept of a plan: how many periods it accrues and the first day of the first one. It carries no price:
 * the accrual engine resolves it by date in the price lists. An item is never edited; while the plan is a draft it is
 * removed and added again.
 *
 * @param periodsCount how many periods are accrued, from 1 to 36
 * @param firstPeriodOn the first day of the first period: the first day of a month, between the years 2000 and 2100
 */
public record ChargePlanItem(UUID id, UUID planId, UUID paymentConceptId, int periodsCount, LocalDate firstPeriodOn,
        Instant createdAt) {

    public static final int MIN_PERIODS = 1;
    public static final int MAX_PERIODS = 36;
    static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    static final LocalDate LATEST = LocalDate.of(2100, 12, 31);

    public ChargePlanItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        if (planId == null || paymentConceptId == null || firstPeriodOn == null
                || periodsCount < MIN_PERIODS || periodsCount > MAX_PERIODS
                || firstPeriodOn.getDayOfMonth() != 1
                || firstPeriodOn.isBefore(EARLIEST) || firstPeriodOn.isAfter(LATEST)) {
            throw new InvalidChargesValueException();
        }
    }
}
