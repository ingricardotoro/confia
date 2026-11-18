package com.confia.charges.infrastructure;

import static confia.generated.jooq.tables.ChargesPlanItem.CHARGES_PLAN_ITEM;

import com.confia.charges.application.ChargePlanItemRepository;
import com.confia.charges.domain.ChargePlanItem;
import com.confia.kernel.InstitutionId;
import confia.generated.jooq.tables.records.ChargesPlanItemRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The jOOQ adapter of {@link ChargePlanItemRepository} over {@code charges_plan_item}. It inserts, reads and deletes by
 * the identifier of the item inside its plan; no statement here updates an item (the application role has no UPDATE on the
 * table), and the database refuses any change while the plan is not a draft.
 */
public final class JooqChargePlanItemRepository implements ChargePlanItemRepository {

    private final DSLContext dsl;

    public JooqChargePlanItemRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<ChargePlanItem> findByPlan(InstitutionId institutionId, UUID planId) {
        return dsl.selectFrom(CHARGES_PLAN_ITEM)
                .where(CHARGES_PLAN_ITEM.INSTITUTION_ID.eq(institutionId.value()))
                .and(CHARGES_PLAN_ITEM.PLAN_ID.eq(planId))
                .orderBy(CHARGES_PLAN_ITEM.CREATED_AT.asc(), CHARGES_PLAN_ITEM.ID.asc())
                .fetch(JooqChargePlanItemRepository::toDomain);
    }

    @Override
    public Optional<ChargePlanItem> find(InstitutionId institutionId, UUID planId, UUID itemId) {
        return dsl.selectFrom(CHARGES_PLAN_ITEM)
                .where(CHARGES_PLAN_ITEM.INSTITUTION_ID.eq(institutionId.value()))
                .and(CHARGES_PLAN_ITEM.PLAN_ID.eq(planId))
                .and(CHARGES_PLAN_ITEM.ID.eq(itemId))
                .fetchOptional().map(JooqChargePlanItemRepository::toDomain);
    }

    @Override
    public long countByPlan(InstitutionId institutionId, UUID planId) {
        return dsl.fetchCount(CHARGES_PLAN_ITEM, CHARGES_PLAN_ITEM.INSTITUTION_ID.eq(institutionId.value())
                .and(CHARGES_PLAN_ITEM.PLAN_ID.eq(planId)));
    }

    @Override
    public void insert(InstitutionId institutionId, ChargePlanItem item) {
        ChargesWrites.translating(() -> dsl.insertInto(CHARGES_PLAN_ITEM)
                .set(CHARGES_PLAN_ITEM.INSTITUTION_ID, institutionId.value())
                .set(CHARGES_PLAN_ITEM.ID, item.id())
                .set(CHARGES_PLAN_ITEM.PLAN_ID, item.planId())
                .set(CHARGES_PLAN_ITEM.PAYMENT_CONCEPT_ID, item.paymentConceptId())
                .set(CHARGES_PLAN_ITEM.PERIODS_COUNT, item.periodsCount())
                .set(CHARGES_PLAN_ITEM.FIRST_PERIOD_ON, item.firstPeriodOn())
                .set(CHARGES_PLAN_ITEM.CREATED_AT, ChargesWrites.toDatabase(item.createdAt()))
                .execute());
    }

    @Override
    public boolean delete(InstitutionId institutionId, UUID planId, UUID itemId) {
        return dsl.deleteFrom(CHARGES_PLAN_ITEM)
                .where(CHARGES_PLAN_ITEM.INSTITUTION_ID.eq(institutionId.value()))
                .and(CHARGES_PLAN_ITEM.PLAN_ID.eq(planId))
                .and(CHARGES_PLAN_ITEM.ID.eq(itemId))
                .execute() == 1;
    }

    private static ChargePlanItem toDomain(ChargesPlanItemRecord row) {
        return new ChargePlanItem(row.getId(), row.getPlanId(), row.getPaymentConceptId(), row.getPeriodsCount(),
                row.getFirstPeriodOn(), ChargesWrites.fromDatabase(row.getCreatedAt()));
    }
}
