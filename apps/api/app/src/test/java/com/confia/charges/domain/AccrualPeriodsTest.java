package com.confia.charges.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The calendar of the engine: periods of a plan item by frequency, due day and the one-time rule. */
class AccrualPeriodsTest {

    private static final LocalDate FEBRUARY = LocalDate.parse("2027-02-01");

    @Test
    void aMonthlyItemHasOneCalendarMonthPerPeriod() {
        List<AccrualPeriod> periods = AccrualPeriods.of(PlanFrequency.MONTHLY, 3, FEBRUARY, 5);

        assertThat(periods).extracting(AccrualPeriod::label).containsExactly("2027-02", "2027-03", "2027-04");
        assertThat(periods.get(0).start()).isEqualTo("2027-02-01");
        assertThat(periods.get(0).end()).isEqualTo("2027-02-28");
        assertThat(periods.get(1).end()).isEqualTo("2027-03-31");
        assertThat(periods.get(0).dueOn()).isEqualTo("2027-02-05");
    }

    @Test
    void aBimonthlyItemStartsEveryTwoMonthsAndEndsTheDayBeforeTheNextStart() {
        List<AccrualPeriod> periods = AccrualPeriods.of(PlanFrequency.BIMONTHLY, 3, LocalDate.parse("2027-01-01"), 10);

        assertThat(periods).extracting(AccrualPeriod::start)
                .containsExactly(LocalDate.parse("2027-01-01"), LocalDate.parse("2027-03-01"),
                        LocalDate.parse("2027-05-01"));
        assertThat(periods).extracting(AccrualPeriod::end)
                .containsExactly(LocalDate.parse("2027-02-28"), LocalDate.parse("2027-04-30"),
                        LocalDate.parse("2027-06-30"));
        assertThat(periods.get(1).label()).isEqualTo("2027-03");
    }

    @Test
    void anAnnualItemCoversTwelveMonthsAndAnnualPeriodsFollowEachOther() {
        List<AccrualPeriod> periods = AccrualPeriods.of(PlanFrequency.ANNUAL, 2, LocalDate.parse("2027-02-01"), 28);

        assertThat(periods.get(0).end()).isEqualTo("2028-01-31");
        assertThat(periods.get(1).start()).isEqualTo("2028-02-01");
        assertThat(periods.get(1).end()).isEqualTo("2029-01-31");
    }

    @Test
    void theDueDayOf28ExistsInFebruaryOfALeapYear() {
        AccrualPeriod february = AccrualPeriods.of(PlanFrequency.MONTHLY, 1, LocalDate.parse("2028-02-01"), 28).get(0);

        assertThat(february.dueOn()).isEqualTo("2028-02-28");
        assertThat(february.end()).isEqualTo("2028-02-29");
    }

    @Test
    void theDueDayFallsInTheMonthWherePeriodStartsEvenForABimonthlyPeriod() {
        AccrualPeriod period = AccrualPeriods.of(PlanFrequency.BIMONTHLY, 1, LocalDate.parse("2027-01-01"), 15).get(0);

        assertThat(period.dueOn()).isEqualTo("2027-01-15");
    }

    @Test
    void aOneTimeItemIsASingleMonth() {
        List<AccrualPeriod> periods = AccrualPeriods.of(PlanFrequency.ONE_TIME, 1, FEBRUARY, 1);

        assertThat(periods).hasSize(1);
        assertThat(periods.get(0).end()).isEqualTo("2027-02-28");
    }

    @Test
    void aOneTimeItemWithMoreThanOnePeriodIsInvalid() {
        assertThatThrownBy(() -> AccrualPeriods.of(PlanFrequency.ONE_TIME, 2, FEBRUARY, 1))
                .isInstanceOf(InvalidPlanItemException.class);
    }

    @Test
    void valuesOutsideTheRulesOfThePlanAreRefused() {
        assertThatThrownBy(() -> AccrualPeriods.of(PlanFrequency.MONTHLY, 0, FEBRUARY, 5))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> AccrualPeriods.of(PlanFrequency.MONTHLY, 37, FEBRUARY, 5))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> AccrualPeriods.of(PlanFrequency.MONTHLY, 1, LocalDate.parse("2027-02-02"), 5))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> AccrualPeriods.of(PlanFrequency.MONTHLY, 1, FEBRUARY, 29))
                .isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void aPlanListsThePeriodsOfAllItsItemsAndARunProcessesTheOnesThatStartAtItsPeriod() {
        Instant now = Instant.parse("2026-11-16T12:00:00Z");
        UUID year = UUID.randomUUID();
        ChargePlan plan = ChargePlan.create(UUID.randomUUID(), year, "Primary 2027", PlanTargetScope.GRADE,
                UUID.randomUUID(), PlanFrequency.MONTHLY, 5, now);
        ChargePlanItem tuition = new ChargePlanItem(UUID.randomUUID(), plan.id(), UUID.randomUUID(), 3, FEBRUARY, now);
        ChargePlanItem fee = new ChargePlanItem(UUID.randomUUID(), plan.id(), UUID.randomUUID(), 1,
                LocalDate.parse("2027-03-01"), now);

        List<AccrualPeriods.PlanPeriod> all = AccrualPeriods.of(plan, List.of(tuition, fee));

        assertThat(all).hasSize(4);
        assertThat(AccrualPeriods.starts(all)).containsExactly(FEBRUARY, LocalDate.parse("2027-03-01"),
                LocalDate.parse("2027-04-01"));
        assertThat(AccrualPeriods.startingAt(all, LocalDate.parse("2027-03-01")))
                .extracting(AccrualPeriods.PlanPeriod::planItemId).containsExactlyInAnyOrder(tuition.id(), fee.id());
        assertThat(AccrualPeriods.startingAt(all, LocalDate.parse("2027-05-01"))).isEmpty();
    }
}
