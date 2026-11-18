package com.confia.charges.infrastructure;

import static confia.generated.jooq.tables.ChargesAccrualRunItem.CHARGES_ACCRUAL_RUN_ITEM;

import com.confia.charges.application.AccrualOutcome;
import com.confia.charges.application.AccrualRunItemRepository;
import com.confia.charges.application.Page;
import com.confia.charges.application.RunItem;
import com.confia.charges.domain.AccrualErrorCode;
import com.confia.kernel.InstitutionId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

/**
 * The jOOQ adapter of {@link AccrualRunItemRepository} over {@code charges_accrual_run_item}. It inserts and reads: no
 * statement here updates or deletes an item, and the database refuses them to every role (INV-47).
 */
public final class JooqAccrualRunItemRepository implements AccrualRunItemRepository {

    private final DSLContext dsl;

    public JooqAccrualRunItemRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insertAll(InstitutionId institution, UUID runId, List<RunItem> items) {
        for (RunItem item : items) {
            dsl.insertInto(CHARGES_ACCRUAL_RUN_ITEM)
                    .set(CHARGES_ACCRUAL_RUN_ITEM.INSTITUTION_ID, institution.value())
                    .set(CHARGES_ACCRUAL_RUN_ITEM.ID, UUID.randomUUID())
                    .set(CHARGES_ACCRUAL_RUN_ITEM.RUN_ID, runId)
                    .set(CHARGES_ACCRUAL_RUN_ITEM.STUDENT_ID, item.studentId())
                    .set(CHARGES_ACCRUAL_RUN_ITEM.PAYMENT_CONCEPT_ID, item.paymentConceptId())
                    .set(CHARGES_ACCRUAL_RUN_ITEM.OUTCOME, item.outcome().name())
                    .set(CHARGES_ACCRUAL_RUN_ITEM.ERROR_CODE, item.errorCode() == null ? null : item.errorCode().name())
                    .set(CHARGES_ACCRUAL_RUN_ITEM.CHARGE_ID, item.chargeId())
                    .set(CHARGES_ACCRUAL_RUN_ITEM.EXISTING_CHARGE_ID, item.existingChargeId())
                    .execute();
        }
    }

    @Override
    public Set<UUID> processedStudents(InstitutionId institution, UUID runId) {
        return new HashSet<>(dsl.selectDistinct(CHARGES_ACCRUAL_RUN_ITEM.STUDENT_ID).from(CHARGES_ACCRUAL_RUN_ITEM)
                .where(CHARGES_ACCRUAL_RUN_ITEM.INSTITUTION_ID.eq(institution.value()))
                .and(CHARGES_ACCRUAL_RUN_ITEM.RUN_ID.eq(runId)).fetch(CHARGES_ACCRUAL_RUN_ITEM.STUDENT_ID));
    }

    @Override
    public RunItemCounts counts(InstitutionId institution, UUID runId) {
        Record row = dsl.select(
                DSL.countDistinct(CHARGES_ACCRUAL_RUN_ITEM.STUDENT_ID),
                DSL.count().filterWhere(CHARGES_ACCRUAL_RUN_ITEM.OUTCOME.eq(AccrualOutcome.CREATED.name())),
                DSL.count().filterWhere(CHARGES_ACCRUAL_RUN_ITEM.OUTCOME.in(AccrualOutcome.ALREADY_ACCRUED.name(),
                        AccrualOutcome.ALREADY_ACCRUED_OTHER_PLAN.name())),
                DSL.count().filterWhere(CHARGES_ACCRUAL_RUN_ITEM.OUTCOME.eq(AccrualOutcome.ERROR.name())))
                .from(CHARGES_ACCRUAL_RUN_ITEM)
                .where(CHARGES_ACCRUAL_RUN_ITEM.INSTITUTION_ID.eq(institution.value()))
                .and(CHARGES_ACCRUAL_RUN_ITEM.RUN_ID.eq(runId)).fetchOne();
        return new RunItemCounts(row.get(0, Integer.class), row.get(1, Integer.class), row.get(2, Integer.class),
                row.get(3, Integer.class));
    }

    @Override
    public Page<RunItem> page(InstitutionId institution, UUID runId, int page, int size) {
        Long total = dsl.selectCount().from(CHARGES_ACCRUAL_RUN_ITEM)
                .where(CHARGES_ACCRUAL_RUN_ITEM.INSTITUTION_ID.eq(institution.value()))
                .and(CHARGES_ACCRUAL_RUN_ITEM.RUN_ID.eq(runId)).fetchOne(0, Long.class);
        List<RunItem> items = dsl.selectFrom(CHARGES_ACCRUAL_RUN_ITEM)
                .where(CHARGES_ACCRUAL_RUN_ITEM.INSTITUTION_ID.eq(institution.value()))
                .and(CHARGES_ACCRUAL_RUN_ITEM.RUN_ID.eq(runId))
                .orderBy(CHARGES_ACCRUAL_RUN_ITEM.STUDENT_ID, CHARGES_ACCRUAL_RUN_ITEM.PAYMENT_CONCEPT_ID)
                .limit(size).offset((long) page * size)
                .fetch(row -> new RunItem(row.getStudentId(), row.getPaymentConceptId(),
                        AccrualOutcome.valueOf(row.getOutcome()),
                        row.getErrorCode() == null ? null : AccrualErrorCode.valueOf(row.getErrorCode()),
                        row.getChargeId(), row.getExistingChargeId()));
        return new Page<>(items, total == null ? 0 : total, page, size);
    }
}
