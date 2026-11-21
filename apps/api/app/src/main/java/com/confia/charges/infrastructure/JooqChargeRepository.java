package com.confia.charges.infrastructure;

import static confia.generated.jooq.tables.ChargesCharge.CHARGES_CHARGE;
import static confia.generated.jooq.tables.ChargesChargeVoid.CHARGES_CHARGE_VOID;

import com.confia.charges.application.ChargeFilter;
import com.confia.charges.application.ChargeRepository;
import com.confia.charges.application.ChargeRow;
import com.confia.charges.application.Page;
import com.confia.charges.application.PageRequest;
import com.confia.charges.domain.ChargeStatus;
import com.confia.charges.domain.IntegrityCheck;
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
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.OrderField;
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
    public java.util.Optional<ChargeForVoid> findForVoid(InstitutionId institution, UUID chargeId) {
        return dsl.select(CHARGES_CHARGE.ID, CHARGES_CHARGE.STUDENT_ID, CHARGES_CHARGE.LEDGER_TRANSACTION_ID,
                CHARGES_CHARGE.TOTAL_AMOUNT, CHARGES_CHARGE.TOTAL_CURRENCY, CHARGES_CHARGE.PERIOD_START)
                .from(CHARGES_CHARGE)
                .where(CHARGES_CHARGE.INSTITUTION_ID.eq(institution.value()))
                .and(CHARGES_CHARGE.ID.eq(chargeId))
                .fetchOptional(row -> new ChargeForVoid(row.get(CHARGES_CHARGE.ID), row.get(CHARGES_CHARGE.STUDENT_ID),
                        row.get(CHARGES_CHARGE.LEDGER_TRANSACTION_ID),
                        MoneyColumns.toMoney(row.get(CHARGES_CHARGE.TOTAL_AMOUNT), row.get(CHARGES_CHARGE.TOTAL_CURRENCY)),
                        row.get(CHARGES_CHARGE.PERIOD_START)));
    }

    @Override
    public List<ChargeLabelRow> findLabels(InstitutionId institution, java.util.Collection<UUID> chargeIds) {
        if (chargeIds.isEmpty()) {
            return List.of();
        }
        return dsl.select(CHARGES_CHARGE.ID, CHARGES_CHARGE.PAYMENT_CONCEPT_ID, CHARGES_CHARGE.PERIOD_LABEL,
                CHARGES_CHARGE.DUE_ON).from(CHARGES_CHARGE)
                .where(CHARGES_CHARGE.INSTITUTION_ID.eq(institution.value()))
                .and(CHARGES_CHARGE.ID.in(chargeIds))
                .fetch(row -> new ChargeLabelRow(row.get(CHARGES_CHARGE.ID), row.get(CHARGES_CHARGE.PAYMENT_CONCEPT_ID),
                        row.get(CHARGES_CHARGE.PERIOD_LABEL), row.get(CHARGES_CHARGE.DUE_ON)));
    }

    @Override
    public List<ChargeForPayment> findActiveByStudent(InstitutionId institution, UUID studentId, CurrencyCode currency) {
        return dsl.select(CHARGES_CHARGE.ID, CHARGES_CHARGE.PAYMENT_CONCEPT_ID, CHARGES_CHARGE.PERIOD_START,
                CHARGES_CHARGE.DUE_ON, CHARGES_CHARGE.TOTAL_AMOUNT, CHARGES_CHARGE.TOTAL_CURRENCY)
                .from(CHARGES_CHARGE)
                .where(CHARGES_CHARGE.INSTITUTION_ID.eq(institution.value()))
                .and(CHARGES_CHARGE.STUDENT_ID.eq(studentId))
                .and(CHARGES_CHARGE.TOTAL_CURRENCY.eq(currency.name()))
                .andNotExists(dsl.selectOne().from(CHARGES_CHARGE_VOID)
                        .where(CHARGES_CHARGE_VOID.INSTITUTION_ID.eq(CHARGES_CHARGE.INSTITUTION_ID))
                        .and(CHARGES_CHARGE_VOID.CHARGE_ID.eq(CHARGES_CHARGE.ID)))
                .fetch(row -> new ChargeForPayment(row.get(CHARGES_CHARGE.ID), row.get(CHARGES_CHARGE.PAYMENT_CONCEPT_ID),
                        row.get(CHARGES_CHARGE.PERIOD_START), row.get(CHARGES_CHARGE.DUE_ON),
                        MoneyColumns.toMoney(row.get(CHARGES_CHARGE.TOTAL_AMOUNT),
                                row.get(CHARGES_CHARGE.TOTAL_CURRENCY))));
    }

    @Override
    public Page<ChargeRow> listByStudent(InstitutionId institution, UUID studentId, ChargeFilter filter,
            PageRequest page) {
        Condition where = CHARGES_CHARGE.INSTITUTION_ID.eq(institution.value())
                .and(CHARGES_CHARGE.STUDENT_ID.eq(studentId));
        if (filter.periodFrom() != null) {
            where = where.and(CHARGES_CHARGE.PERIOD_START.ge(filter.periodFrom()));
        }
        if (filter.periodTo() != null) {
            where = where.and(CHARGES_CHARGE.PERIOD_START.le(filter.periodTo()));
        }
        if (filter.statusOrNull() == ChargeStatus.VOIDED) {
            where = where.and(CHARGES_CHARGE_VOID.ID.isNotNull());
        } else if (filter.statusOrNull() == ChargeStatus.ACTIVE) {
            where = where.and(CHARGES_CHARGE_VOID.ID.isNull());
        }
        long total = dsl.selectCount().from(CHARGES_CHARGE).leftJoin(CHARGES_CHARGE_VOID)
                .on(CHARGES_CHARGE_VOID.INSTITUTION_ID.eq(CHARGES_CHARGE.INSTITUTION_ID))
                .and(CHARGES_CHARGE_VOID.CHARGE_ID.eq(CHARGES_CHARGE.ID))
                .where(where).fetchOne(0, long.class);
        List<ChargeRow> rows = dsl.select(CHARGES_CHARGE.ID, CHARGES_CHARGE.PAYMENT_CONCEPT_ID,
                CHARGES_CHARGE.CONCEPT_CATEGORY, CHARGES_CHARGE.PERIOD_LABEL, CHARGES_CHARGE.PERIOD_START,
                CHARGES_CHARGE.PERIOD_END, CHARGES_CHARGE.DUE_ON, CHARGES_CHARGE.ORIGIN, CHARGES_CHARGE.BASE_AMOUNT,
                CHARGES_CHARGE.BASE_CURRENCY, CHARGES_CHARGE.TAX_AMOUNT, CHARGES_CHARGE.TAX_CURRENCY,
                CHARGES_CHARGE.TOTAL_AMOUNT, CHARGES_CHARGE.TOTAL_CURRENCY, CHARGES_CHARGE_VOID.ID,
                CHARGES_CHARGE_VOID.REVERSAL_TRANSACTION_ID, CHARGES_CHARGE_VOID.VOIDED_AT, CHARGES_CHARGE_VOID.REASON)
                .from(CHARGES_CHARGE).leftJoin(CHARGES_CHARGE_VOID)
                .on(CHARGES_CHARGE_VOID.INSTITUTION_ID.eq(CHARGES_CHARGE.INSTITUTION_ID))
                .and(CHARGES_CHARGE_VOID.CHARGE_ID.eq(CHARGES_CHARGE.ID))
                .where(where)
                .orderBy(orders(page))
                .limit(page.size()).offset(page.offset())
                .fetch(JooqChargeRepository::toRow);
        return new Page<>(rows, total, page.page(), page.size());
    }

    /** The sort field was checked against {@code ChargeFilter.SORT_FIELDS} by the request; anything else is a defect, not an input. */
    private static OrderField<?> order(PageRequest page) {
        var field = switch (page.sortField()) {
            case "periodStart" -> CHARGES_CHARGE.PERIOD_START;
            case "dueOn" -> CHARGES_CHARGE.DUE_ON;
            case "total" -> CHARGES_CHARGE.TOTAL_AMOUNT;
            default -> throw new IllegalArgumentException("unknown sort field");
        };
        return page.ascending() ? field.asc() : field.desc();
    }

    /** A sort by total goes by currency first: amounts of two currencies are not comparable. */
    private static List<OrderField<?>> orders(PageRequest page) {
        List<OrderField<?>> fields = new ArrayList<>();
        if (page.sortField().equals("total")) {
            fields.add(CHARGES_CHARGE.TOTAL_CURRENCY.asc());
        }
        fields.add(order(page));
        fields.add(CHARGES_CHARGE.ID.asc());
        return fields;
    }

    private static ChargeRow toRow(Record row) {
        ChargeRow.VoidInfo voided = row.get(CHARGES_CHARGE_VOID.ID) == null ? null
                : new ChargeRow.VoidInfo(row.get(CHARGES_CHARGE_VOID.ID),
                        row.get(CHARGES_CHARGE_VOID.REVERSAL_TRANSACTION_ID),
                        ChargesWrites.fromDatabase(row.get(CHARGES_CHARGE_VOID.VOIDED_AT)),
                        row.get(CHARGES_CHARGE_VOID.REASON));
        return new ChargeRow(row.get(CHARGES_CHARGE.ID), row.get(CHARGES_CHARGE.PAYMENT_CONCEPT_ID),
                row.get(CHARGES_CHARGE.CONCEPT_CATEGORY), row.get(CHARGES_CHARGE.PERIOD_LABEL),
                row.get(CHARGES_CHARGE.PERIOD_START), row.get(CHARGES_CHARGE.PERIOD_END), row.get(CHARGES_CHARGE.DUE_ON),
                row.get(CHARGES_CHARGE.ORIGIN),
                MoneyColumns.toMoney(row.get(CHARGES_CHARGE.BASE_AMOUNT), row.get(CHARGES_CHARGE.BASE_CURRENCY)),
                MoneyColumns.toMoney(row.get(CHARGES_CHARGE.TAX_AMOUNT), row.get(CHARGES_CHARGE.TAX_CURRENCY)),
                MoneyColumns.toMoney(row.get(CHARGES_CHARGE.TOTAL_AMOUNT), row.get(CHARGES_CHARGE.TOTAL_CURRENCY)),
                voided);
    }

    @Override
    public List<IntegrityCheck.ChargeFact> integrityFacts(InstitutionId institution) {
        return dsl.select(CHARGES_CHARGE.ID, CHARGES_CHARGE.LEDGER_TRANSACTION_ID, CHARGES_CHARGE.TOTAL_AMOUNT,
                CHARGES_CHARGE.TOTAL_CURRENCY, CHARGES_CHARGE_VOID.REVERSAL_TRANSACTION_ID)
                .from(CHARGES_CHARGE).leftJoin(CHARGES_CHARGE_VOID)
                .on(CHARGES_CHARGE_VOID.INSTITUTION_ID.eq(CHARGES_CHARGE.INSTITUTION_ID))
                .and(CHARGES_CHARGE_VOID.CHARGE_ID.eq(CHARGES_CHARGE.ID))
                .where(CHARGES_CHARGE.INSTITUTION_ID.eq(institution.value()))
                .orderBy(CHARGES_CHARGE.ID)
                .fetch(row -> new IntegrityCheck.ChargeFact(row.get(CHARGES_CHARGE.ID),
                        row.get(CHARGES_CHARGE.LEDGER_TRANSACTION_ID),
                        MoneyColumns.toMoney(row.get(CHARGES_CHARGE.TOTAL_AMOUNT), row.get(CHARGES_CHARGE.TOTAL_CURRENCY)),
                        row.get(CHARGES_CHARGE_VOID.REVERSAL_TRANSACTION_ID)));
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
