package com.confia.charges.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The rules of an item of a charge plan: periods from 1 to 36 and a first period that is the first day of a month. */
class ChargePlanItemTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final LocalDate FEBRUARY = LocalDate.parse("2027-02-01");

    private static ChargePlanItem item(int periods, LocalDate first) {
        return new ChargePlanItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), periods, first, NOW);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 36})
    void aNumberOfPeriodsFromOneToThirtySixIsAccepted(int periods) {
        assertThat(item(periods, FEBRUARY).periodsCount()).isEqualTo(periods);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 37, 100})
    void aNumberOfPeriodsOutsideOneToThirtySixIsRefused(int periods) {
        assertThatThrownBy(() -> item(periods, FEBRUARY)).isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void theFirstPeriodIsTheFirstDayOfAMonthInsideTheWindow() {
        assertThat(item(1, LocalDate.parse("2000-01-01")).firstPeriodOn()).isEqualTo("2000-01-01");
        assertThat(item(1, LocalDate.parse("2100-12-01")).firstPeriodOn()).isEqualTo("2100-12-01");
        for (String day : new String[] {"2027-02-02", "2027-02-28", "2027-01-31", "1999-12-01", "2101-01-01"}) {
            assertThatThrownBy(() -> item(1, LocalDate.parse(day))).as(day)
                    .isInstanceOf(InvalidChargesValueException.class);
        }
        assertThatThrownBy(() -> item(1, null)).isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void anItemNeedsItsPlanAndItsConcept() {
        assertThatThrownBy(() -> new ChargePlanItem(UUID.randomUUID(), null, UUID.randomUUID(), 1, FEBRUARY, NOW))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new ChargePlanItem(UUID.randomUUID(), UUID.randomUUID(), null, 1, FEBRUARY, NOW))
                .isInstanceOf(InvalidChargesValueException.class);
    }
}
