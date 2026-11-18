package com.confia.charges.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The calendar of the engine (docs/cortes/charges-accrual.md, engine point 1): the periods of a plan item. For {@code i =
 * 0 .. periodsCount - 1} the period starts {@code i x L} months after the first day of the first period and ends the day
 * before it would start {@code L} months later, with {@code L} = 1 for a monthly plan, 2 for a bimonthly one, 12 for an
 * annual one and 1 for a one-time one, which has exactly one period. A charge falls due on the due day of the month in which
 * its period starts; the day is from 1 to 28, so it exists in every month. The browser never computes a period: the server
 * lists them.
 */
public final class AccrualPeriods {

    private AccrualPeriods() {
    }

    /** One period of an item of the plan, with the item that produces it. */
    public record PlanPeriod(UUID planItemId, UUID paymentConceptId, AccrualPeriod period) {
    }

    /**
     * The periods of one item.
     *
     * @throws InvalidPlanItemException when a one-time plan asks for more than one period
     */
    public static List<AccrualPeriod> of(PlanFrequency frequency, int periodsCount, LocalDate firstPeriodOn,
            int dueDayOfMonth) {
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(firstPeriodOn, "firstPeriodOn");
        if (periodsCount < ChargePlanItem.MIN_PERIODS || periodsCount > ChargePlanItem.MAX_PERIODS
                || firstPeriodOn.getDayOfMonth() != 1 || dueDayOfMonth < ChargePlan.MIN_DUE_DAY
                || dueDayOfMonth > ChargePlan.MAX_DUE_DAY) {
            throw new InvalidChargesValueException();
        }
        if (frequency == PlanFrequency.ONE_TIME && periodsCount != 1) {
            throw new InvalidPlanItemException();
        }
        int length = lengthInMonths(frequency);
        List<AccrualPeriod> periods = new ArrayList<>(periodsCount);
        for (int i = 0; i < periodsCount; i++) {
            LocalDate start = firstPeriodOn.plusMonths((long) i * length);
            LocalDate end = start.plusMonths(length).minusDays(1);
            periods.add(new AccrualPeriod(start, end, start.withDayOfMonth(dueDayOfMonth),
                    YearMonth.from(start).toString()));
        }
        return List.copyOf(periods);
    }

    /** Every period of every item of the plan, in order of start and then of item creation (the order of {@code items}). */
    public static List<PlanPeriod> of(ChargePlan plan, List<ChargePlanItem> items) {
        Objects.requireNonNull(plan, "plan");
        List<PlanPeriod> all = new ArrayList<>();
        for (ChargePlanItem item : items) {
            for (AccrualPeriod period : of(plan.frequency(), item.periodsCount(), item.firstPeriodOn(),
                    plan.dueDayOfMonth())) {
                all.add(new PlanPeriod(item.id(), item.paymentConceptId(), period));
            }
        }
        all.sort((a, b) -> a.period().start().compareTo(b.period().start()));
        return List.copyOf(all);
    }

    /** The periods that start at {@code periodStart}: what a run for that period processes. */
    public static List<PlanPeriod> startingAt(List<PlanPeriod> periods, LocalDate periodStart) {
        return periods.stream().filter(p -> p.period().start().equals(periodStart)).toList();
    }

    /** The distinct starts of the periods of the plan, ascending. */
    public static List<LocalDate> starts(List<PlanPeriod> periods) {
        return periods.stream().map(p -> p.period().start()).distinct().sorted().toList();
    }

    private static int lengthInMonths(PlanFrequency frequency) {
        return switch (frequency) {
            case MONTHLY, ONE_TIME -> 1;
            case BIMONTHLY -> 2;
            case ANNUAL -> 12;
        };
    }
}
