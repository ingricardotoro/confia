package com.confia.organization.infrastructure;

import static confia.generated.jooq.tables.OrganizationGrade.ORGANIZATION_GRADE;

import com.confia.kernel.InstitutionId;
import com.confia.organization.application.GradeRepository;
import com.confia.organization.application.Page;
import com.confia.organization.application.PageRequest;
import com.confia.organization.domain.Grade;
import com.confia.organization.domain.ResourceNotFoundException;
import confia.generated.jooq.tables.records.OrganizationGradeRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;

/** The jOOQ adapter of {@link GradeRepository} over {@code organization_grade}. */
public final class JooqGradeRepository implements GradeRepository {

    private static final Map<String, Field<?>> SORTABLE = Map.of(
            "name", ORGANIZATION_GRADE.NAME,
            "ordinal", ORGANIZATION_GRADE.ORDINAL);

    private final DSLContext dsl;

    public JooqGradeRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Grade> findById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(ORGANIZATION_GRADE)
                .where(ORGANIZATION_GRADE.INSTITUTION_ID.eq(institutionId.value()))
                .and(ORGANIZATION_GRADE.ID.eq(id))
                .fetchOptional().map(JooqGradeRepository::toDomain);
    }

    @Override
    public void insert(InstitutionId institutionId, Grade grade) {
        OrganizationWrites.translating(() -> dsl.insertInto(ORGANIZATION_GRADE)
                .set(ORGANIZATION_GRADE.INSTITUTION_ID, institutionId.value())
                .set(ORGANIZATION_GRADE.ID, grade.id())
                .set(ORGANIZATION_GRADE.MODALITY_ID, grade.modalityId())
                .set(ORGANIZATION_GRADE.NAME, grade.name())
                .set(ORGANIZATION_GRADE.ORDINAL, grade.ordinal())
                .set(ORGANIZATION_GRADE.IS_ACTIVE, grade.active())
                .set(ORGANIZATION_GRADE.CREATED_AT, OrganizationWrites.toDatabase(grade.createdAt()))
                .set(ORGANIZATION_GRADE.UPDATED_AT, OrganizationWrites.toDatabase(grade.updatedAt()))
                .execute());
    }

    @Override
    public void update(InstitutionId institutionId, Grade grade) {
        OrganizationWrites.translating(() -> {
            int rows = dsl.update(ORGANIZATION_GRADE)
                    .set(ORGANIZATION_GRADE.NAME, grade.name())
                    .set(ORGANIZATION_GRADE.ORDINAL, grade.ordinal())
                    .set(ORGANIZATION_GRADE.IS_ACTIVE, grade.active())
                    .set(ORGANIZATION_GRADE.UPDATED_AT, OrganizationWrites.toDatabase(grade.updatedAt()))
                    .where(ORGANIZATION_GRADE.INSTITUTION_ID.eq(institutionId.value()))
                    .and(ORGANIZATION_GRADE.ID.eq(grade.id()))
                    .execute();
            if (rows == 0) {
                throw new ResourceNotFoundException();
            }
        });
    }

    @Override
    public Page<Grade> list(InstitutionId institutionId, UUID modalityId, PageRequest request) {
        Condition scope = ORGANIZATION_GRADE.INSTITUTION_ID.eq(institutionId.value());
        if (modalityId != null) {
            scope = scope.and(ORGANIZATION_GRADE.MODALITY_ID.eq(modalityId));
        }
        long total = dsl.fetchCount(ORGANIZATION_GRADE, scope);
        List<Grade> rows = dsl.selectFrom(ORGANIZATION_GRADE).where(scope)
                .orderBy(order(request), ORGANIZATION_GRADE.ID.asc())
                .limit(request.size()).offset(request.offset())
                .fetch(JooqGradeRepository::toDomain);
        return new Page<>(rows, total, request.page(), request.size());
    }

    private static SortField<?> order(PageRequest request) {
        Field<?> field = SORTABLE.get(request.sortField());
        return request.ascending() ? field.asc() : field.desc();
    }

    private static Grade toDomain(OrganizationGradeRecord row) {
        return new Grade(row.getId(), row.getModalityId(), row.getName(), row.getOrdinal(),
                row.getIsActive(), OrganizationWrites.fromDatabase(row.getCreatedAt()),
                OrganizationWrites.fromDatabase(row.getUpdatedAt()));
    }
}
