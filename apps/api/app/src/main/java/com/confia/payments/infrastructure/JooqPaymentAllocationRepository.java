package com.confia.payments.infrastructure;

import static confia.generated.jooq.tables.PaymentsPaymentAllocation.PAYMENTS_PAYMENT_ALLOCATION;

import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.payments.application.PaymentAllocationRepository;
import com.confia.payments.domain.PaymentAllocation;
import com.confia.shared.infrastructure.money.MoneyColumns;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * The jOOQ adapter of {@link PaymentAllocationRepository} over {@code payments_payment_allocation}. It inserts and reads: no
 * statement here updates or deletes an allocation, and the database refuses them to every role, as it refuses an allocation
 * that would make a charge receive more than its total or a payment apply more than its amount (INV-17, INV-18). The sums are
 * made by the database over exact numerics and read back through {@link MoneyColumns}.
 */
public final class JooqPaymentAllocationRepository implements PaymentAllocationRepository {

    private final DSLContext dsl;

    public JooqPaymentAllocationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institution, PaymentAllocation allocation) {
        dsl.insertInto(PAYMENTS_PAYMENT_ALLOCATION)
                .set(PAYMENTS_PAYMENT_ALLOCATION.INSTITUTION_ID, institution.value())
                .set(PAYMENTS_PAYMENT_ALLOCATION.ID, allocation.id())
                .set(PAYMENTS_PAYMENT_ALLOCATION.PAYMENT_ID, allocation.paymentId())
                .set(PAYMENTS_PAYMENT_ALLOCATION.CHARGE_ID, allocation.chargeId())
                .set(PAYMENTS_PAYMENT_ALLOCATION.AMOUNT, MoneyColumns.amountColumn(allocation.amount()))
                .set(PAYMENTS_PAYMENT_ALLOCATION.CURRENCY, MoneyColumns.currencyColumn(allocation.amount()))
                .set(PAYMENTS_PAYMENT_ALLOCATION.ALLOCATED_AT, PaymentsWrites.toDatabase(allocation.allocatedAt()))
                .execute();
    }

    @Override
    public List<PaymentAllocation> findByPayment(InstitutionId institution, UUID paymentId) {
        return dsl.selectFrom(PAYMENTS_PAYMENT_ALLOCATION)
                .where(PAYMENTS_PAYMENT_ALLOCATION.INSTITUTION_ID.eq(institution.value()))
                .and(PAYMENTS_PAYMENT_ALLOCATION.PAYMENT_ID.eq(paymentId))
                .orderBy(PAYMENTS_PAYMENT_ALLOCATION.ALLOCATED_AT.asc(), PAYMENTS_PAYMENT_ALLOCATION.ID.asc())
                .fetch(row -> new PaymentAllocation(row.getId(), row.getPaymentId(), row.getChargeId(),
                        MoneyColumns.toMoney(row.getAmount(), row.getCurrency()),
                        PaymentsWrites.fromDatabase(row.getAllocatedAt())));
    }

    @Override
    public Map<UUID, Money> allocatedByCharge(InstitutionId institution, Collection<UUID> chargeIds) {
        Map<UUID, Money> sums = new HashMap<>();
        if (chargeIds.isEmpty()) {
            return sums;
        }
        var total = DSL.sum(PAYMENTS_PAYMENT_ALLOCATION.AMOUNT);
        dsl.select(PAYMENTS_PAYMENT_ALLOCATION.CHARGE_ID, PAYMENTS_PAYMENT_ALLOCATION.CURRENCY, total)
                .from(PAYMENTS_PAYMENT_ALLOCATION)
                .where(PAYMENTS_PAYMENT_ALLOCATION.INSTITUTION_ID.eq(institution.value()))
                .and(PAYMENTS_PAYMENT_ALLOCATION.CHARGE_ID.in(chargeIds))
                .groupBy(PAYMENTS_PAYMENT_ALLOCATION.CHARGE_ID, PAYMENTS_PAYMENT_ALLOCATION.CURRENCY)
                .forEach(row -> {
                    BigDecimal sum = row.get(total);
                    sums.put(row.get(PAYMENTS_PAYMENT_ALLOCATION.CHARGE_ID),
                            MoneyColumns.toMoney(sum, row.get(PAYMENTS_PAYMENT_ALLOCATION.CURRENCY)));
                });
        return sums;
    }

    @Override
    public boolean chargeHasAllocations(InstitutionId institution, UUID chargeId) {
        return dsl.fetchExists(PAYMENTS_PAYMENT_ALLOCATION,
                PAYMENTS_PAYMENT_ALLOCATION.INSTITUTION_ID.eq(institution.value())
                        .and(PAYMENTS_PAYMENT_ALLOCATION.CHARGE_ID.eq(chargeId)));
    }
}
