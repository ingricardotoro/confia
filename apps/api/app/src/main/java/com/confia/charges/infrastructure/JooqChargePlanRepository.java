package com.confia.charges.infrastructure;

import static confia.generated.jooq.tables.ChargesPlan.CHARGES_PLAN;

import com.confia.charges.application.ChargePlanFilter;
import com.confia.charges.application.ChargePlanRepository;
import com.confia.charges.application.Page;
import com.confia.charges.application.PageRequest;
import com.confia.charges.domain.ChargePlan;
import com.confia.charges.domain.PlanFrequency;
import com.confia.charges.domain.PlanStatus;
import com.confia.charges.domain.PlanTargetScope;
import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.charges.domain.StaleVersionException;
import com.confia.kernel.InstitutionId;
import confia.generated.jooq.tables.records.ChargesPlanRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.DSL;

/** The jOOQ adapter of {@link ChargePlanRepository} over {@code charges_plan}. */
public final class JooqChargePlanRepository implements ChargePlanRepository {

    private static final Map<String, Field<?>> SORTABLE = Map.of(
            "name", CHARGES_PLAN.NAME,
            "createdAt", CHARGES_PLAN.CREATED_AT);

    private final DSLContext dsl;

    public JooqChargePlanRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<ChargePlan> findById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(CHARGES_PLAN)
                .where(CHARGES_PLAN.INSTITUTION_ID.eq(institutionId.value()))
                .and(CHARGES_PLAN.ID.eq(id))
                .fetchOptional().map(JooqChargePlanRepository::toDomain);
    }

    @Override
    public Optional<ChargePlan> lockById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(CHARGES_PLAN)
                .where(CHARGES_PLAN.INSTITUTION_ID.eq(institutionId.value()))
                .and(CHARGES_PLAN.ID.eq(id))
                .forUpdate()
                .fetchOptional().map(JooqChargePlanRepository::toDomain);
    }

    @Override
    public void insert(InstitutionId institutionId, ChargePlan plan) {
        dsl.insertInto(CHARGES_PLAN)
                .set(CHARGES_PLAN.INSTITUTION_ID, institutionId.value())
                .set(CHARGES_PLAN.ID, plan.id())
                .set(CHARGES_PLAN.ACADEMIC_YEAR_ID, plan.academicYearId())
                .set(CHARGES_PLAN.NAME, plan.name())
                .set(CHARGES_PLAN.TARGET_SCOPE, plan.targetScope().name())
                .set(CHARGES_PLAN.TARGET_REF_ID, plan.targetRefId())
                .set(CHARGES_PLAN.FREQUENCY, plan.frequency().name())
                .set(CHARGES_PLAN.DUE_DAY_OF_MONTH, plan.dueDayOfMonth())
                .set(CHARGES_PLAN.STATUS, plan.status().name())
                .set(CHARGES_PLAN.VERSION, plan.version())
                .set(CHARGES_PLAN.CREATED_AT, ChargesWrites.toDatabase(plan.createdAt()))
                .set(CHARGES_PLAN.UPDATED_AT, ChargesWrites.toDatabase(plan.updatedAt()))
                .execute();
    }

    @Override
    public void update(InstitutionId institutionId, ChargePlan plan, long expectedVersion) {
        // The year, the target and the creation are never written: they are immutable once the plan exists.
        int rows = dsl.update(CHARGES_PLAN)
                .set(CHARGES_PLAN.NAME, plan.name())
                .set(CHARGES_PLAN.FREQUENCY, plan.frequency().name())
                .set(CHARGES_PLAN.DUE_DAY_OF_MONTH, plan.dueDayOfMonth())
                .set(CHARGES_PLAN.STATUS, plan.status().name())
                .set(CHARGES_PLAN.VERSION, plan.version())
                .set(CHARGES_PLAN.UPDATED_AT, ChargesWrites.toDatabase(plan.updatedAt()))
                .where(CHARGES_PLAN.INSTITUTION_ID.eq(institutionId.value()))
                .and(CHARGES_PLAN.ID.eq(plan.id()))
                .and(CHARGES_PLAN.VERSION.eq(expectedVersion))
                .execute();
        if (rows == 0) {
            boolean exists = dsl.fetchExists(CHARGES_PLAN, CHARGES_PLAN.INSTITUTION_ID.eq(institutionId.value())
                    .and(CHARGES_PLAN.ID.eq(plan.id())));
            throw exists ? new StaleVersionException() : new ResourceNotFoundException();
        }
    }

    @Override
    public Page<ChargePlan> list(InstitutionId institutionId, PageRequest request, ChargePlanFilter filter) {
        Condition scope = CHARGES_PLAN.INSTITUTION_ID.eq(institutionId.value());
        if (filter.academicYearId() != null) {
            scope = scope.and(CHARGES_PLAN.ACADEMIC_YEAR_ID.eq(filter.academicYearId()));
        }
        if (filter.targetScope() != null) {
            scope = scope.and(CHARGES_PLAN.TARGET_SCOPE.eq(filter.targetScope().name()));
        }
        if (filter.status() != null) {
            scope = scope.and(CHARGES_PLAN.STATUS.eq(filter.status().name()));
        }
        long total = dsl.fetchCount(CHARGES_PLAN, scope);
        List<ChargePlan> rows = dsl.selectFrom(CHARGES_PLAN).where(scope)
                .orderBy(order(request), CHARGES_PLAN.ID.asc())
                .limit(request.size()).offset(request.offset())
                .fetch(JooqChargePlanRepository::toDomain);
        return new Page<>(rows, total, request.page(), request.size());
    }

    private static SortField<?> order(PageRequest request) {
        // Names compare without regard to capitals, whatever the collation of the database.
        Field<?> field = request.sortField().equals("name") ? DSL.lower(CHARGES_PLAN.NAME)
                : SORTABLE.get(request.sortField());
        return request.ascending() ? field.asc() : field.desc();
    }

    private static ChargePlan toDomain(ChargesPlanRecord row) {
        return new ChargePlan(row.getId(), row.getAcademicYearId(), row.getName(),
                PlanTargetScope.parse(row.getTargetScope()), row.getTargetRefId(),
                PlanFrequency.parse(row.getFrequency()), row.getDueDayOfMonth(),
                PlanStatus.parse(row.getStatus()), row.getVersion(),
                ChargesWrites.fromDatabase(row.getCreatedAt()), ChargesWrites.fromDatabase(row.getUpdatedAt()));
    }
}
