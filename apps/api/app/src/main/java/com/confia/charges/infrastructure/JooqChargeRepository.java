package com.confia.charges.infrastructure;

import static confia.generated.jooq.tables.ChargesCharge.CHARGES_CHARGE;

import com.confia.charges.application.ChargeRepository;
import com.confia.charges.domain.ComputedCharge;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.shared.infrastructure.money.MoneyColumns;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

/**
 * The jOOQ adapter of {@link ChargeRepository} over {@code charges_charge}. It inserts and reads: no statement here updates
 * or deletes a charge, and the database refuses them to every role (INV-47). Amounts go through {@link MoneyColumns} and
 * nowhere else; the sums of a run are made by the database over exact numerics and read back through the same converter.
 */
public final class JooqChargeRepository implements ChargeRepository {

    private static final String AUTOMATIC = "AUTOMATIC";

    private final DSLContext dsl;

    public JooqChargeRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<ExistingCharge> findOverlapping(InstitutionId institution, UUID studentId, UUID paymentConceptId,
            LocalDate periodStart, LocalDate periodEnd) {
        return dsl.select(CHARGES_CHARGE.ID, CHARGES_CHARGE.PLAN_ID, CHARGES_CHARGE.PERIOD_START,
                CHARGES_CHARGE.PERIOD_END)
                .from(CHARGES_CHARGE)
                .where(CHARGES_CHARGE.INSTITUTION_ID.eq(institution.value()))
                .and(CHARGES_CHARGE.STUDENT_ID.eq(studentId))
                .and(CHARGES_CHARGE.PAYMENT_CONCEPT_ID.eq(paymentConceptId))
                .and(CHARGES_CHARGE.ORIGIN.eq(AUTOMATIC))
                .and(CHARGES_CHARGE.PERIOD_START.le(periodEnd))
                .and(CHARGES_CHARGE.PERIOD_END.ge(periodStart))
                .orderBy(CHARGES_CHARGE.PERIOD_START, CHARGES_CHARGE.ID)
                .fetch(row -> new ExistingCharge(row.get(CHARGES_CHARGE.ID), row.get(CHARGES_CHARGE.PLAN_ID),
                        row.get(CHARGES_CHARGE.PERIOD_START), row.get(CHARGES_CHARGE.PERIOD_END)));
    }

    @Override
    public void insertAutomatic(InstitutionId institution, UUID chargeId, ComputedCharge charge, UUID runId,
            UUID ledgerTransactionId, UUID createdBy) {
        Money base = charge.amounts().base();
        dsl.insertInto(CHARGES_CHARGE)
                .set(CHARGES_CHARGE.INSTITUTION_ID, institution.value())
                .set(CHARGES_CHARGE.ID, chargeId)
                .set(CHARGES_CHARGE.STUDENT_ID, charge.studentId())
                .set(CHARGES_CHARGE.ENROLLMENT_ID, charge.enrollmentId())
                .set(CHARGES_CHARGE.PAYMENT_CONCEPT_ID, charge.paymentConceptId())
                .set(CHARGES_CHARGE.CONCEPT_CATEGORY, charge.conceptCategory())
                .set(CHARGES_CHARGE.PLAN_ID, charge.planId())
                .set(CHARGES_CHARGE.PLAN_ITEM_ID, charge.planItemId())
                .set(CHARGES_CHARGE.ACCRUAL_RUN_ID, runId)
                .set(CHARGES_CHARGE.ORIGIN, AUTOMATIC)
                .set(CHARGES_CHARGE.PERIOD_START, charge.period().start())
                .set(CHARGES_CHARGE.PERIOD_END, charge.period().end())
                .set(CHARGES_CHARGE.PERIOD_LABEL, charge.period().label())
                .set(CHARGES_CHARGE.PRICING_DATE, charge.period().start())
                .set(CHARGES_CHARGE.DUE_ON, charge.period().dueOn())
                .set(CHARGES_CHARGE.PRICE_LIST_ITEM_ID, charge.priceListItemId())
                .set(CHARGES_CHARGE.BASE_AMOUNT, MoneyColumns.amountColumn(base))
                .set(CHARGES_CHARGE.BASE_CURRENCY, MoneyColumns.currencyColumn(base))
                .set(CHARGES_CHARGE.DISCOUNT_AMOUNT, MoneyColumns.amountColumn(charge.amounts().discount()))
                .set(CHARGES_CHARGE.DISCOUNT_CURRENCY, MoneyColumns.currencyColumn(charge.amounts().discount()))
                .set(CHARGES_CHARGE.TAX_AMOUNT, MoneyColumns.amountColumn(charge.amounts().tax()))
                .set(CHARGES_CHARGE.TAX_CURRENCY, MoneyColumns.currencyColumn(charge.amounts().tax()))
                .set(CHARGES_CHARGE.TOTAL_AMOUNT, MoneyColumns.amountColumn(charge.amounts().total()))
                .set(CHARGES_CHARGE.TOTAL_CURRENCY, MoneyColumns.currencyColumn(charge.amounts().total()))
                .set(CHARGES_CHARGE.IS_TAXABLE, charge.taxable())
                .set(CHARGES_CHARGE.TAX_RATE_ID, charge.taxRateId())
                .set(CHARGES_CHARGE.TAX_RATE_PERIOD_ID, charge.taxRatePeriodId())
                .set(CHARGES_CHARGE.TAX_RATE_PERCENT, charge.taxPercent() == null ? null : charge.taxPercent().value())
                .set(CHARGES_CHARGE.LEDGER_TRANSACTION_ID, ledgerTransactionId)
                .set(CHARGES_CHARGE.CREATED_BY, createdBy)
                .execute();
    }

    @Override
    public java.util.Map<UUID, StatementCharge> findByLedgerTransactions(InstitutionId institution,
            java.util.Collection<UUID> ledgerTransactionIds) {
        java.util.Map<UUID, StatementCharge> found = new java.util.HashMap<>();
        if (ledgerTransactionIds.isEmpty()) {
            return found;
        }
        dsl.select(CHARGES_CHARGE.LEDGER_TRANSACTION_ID, CHARGES_CHARGE.ID, CHARGES_CHARGE.PAYMENT_CONCEPT_ID,
                CHARGES_CHARGE.CONCEPT_CATEGORY, CHARGES_CHARGE.PERIOD_LABEL, CHARGES_CHARGE.PERIOD_START,
                CHARGES_CHARGE.PERIOD_END, CHARGES_CHARGE.DUE_ON, CHARGES_CHARGE.ORIGIN)
                .from(CHARGES_CHARGE)
                .where(CHARGES_CHARGE.INSTITUTION_ID.eq(institution.value()))
                .and(CHARGES_CHARGE.LEDGER_TRANSACTION_ID.in(ledgerTransactionIds))
                .forEach(row -> found.put(row.get(CHARGES_CHARGE.LEDGER_TRANSACTION_ID),
                        new StatementCharge(row.get(CHARGES_CHARGE.ID), row.get(CHARGES_CHARGE.PAYMENT_CONCEPT_ID),
                                row.get(CHARGES_CHARGE.CONCEPT_CATEGORY), row.get(CHARGES_CHARGE.PERIOD_LABEL),
                                row.get(CHARGES_CHARGE.PERIOD_START), row.get(CHARGES_CHARGE.PERIOD_END),
                                row.get(CHARGES_CHARGE.DUE_ON), row.get(CHARGES_CHARGE.ORIGIN))));
        return found;
    }

    @Override
    public List<CurrencyTotals> totalsOfRun(InstitutionId institution, UUID runId) {
        List<CurrencyTotals> totals = new ArrayList<>();
        for (Record row : dsl.select(CHARGES_CHARGE.BASE_CURRENCY, DSL.count(), DSL.sum(CHARGES_CHARGE.BASE_AMOUNT),
                DSL.sum(CHARGES_CHARGE.TAX_AMOUNT), DSL.sum(CHARGES_CHARGE.TOTAL_AMOUNT))
                .from(CHARGES_CHARGE)
                .where(CHARGES_CHARGE.INSTITUTION_ID.eq(institution.value()))
                .and(CHARGES_CHARGE.ACCRUAL_RUN_ID.eq(runId))
                .groupBy(CHARGES_CHARGE.BASE_CURRENCY).fetch()) {
            String currency = row.get(0, String.class);
            BigDecimal base = row.get(2, BigDecimal.class);
            BigDecimal tax = row.get(3, BigDecimal.class);
            BigDecimal total = row.get(4, BigDecimal.class);
            totals.add(new CurrencyTotals(CurrencyCode.valueOf(currency), row.get(1, Integer.class),
                    MoneyColumns.toMoney(base, currency), MoneyColumns.toMoney(tax, currency),
                    MoneyColumns.toMoney(total, currency)));
        }
        return totals;
    }
}
