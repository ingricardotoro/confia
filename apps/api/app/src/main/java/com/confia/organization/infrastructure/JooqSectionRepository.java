package com.confia.organization.infrastructure;

import static confia.generated.jooq.tables.OrganizationSection.ORGANIZATION_SECTION;

import com.confia.kernel.InstitutionId;
import com.confia.organization.application.Page;
import com.confia.organization.application.PageRequest;
import com.confia.organization.application.SectionRepository;
import com.confia.organization.domain.ResourceNotFoundException;
import com.confia.organization.domain.Section;
import confia.generated.jooq.tables.records.OrganizationSectionRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;

/** The jOOQ adapter of {@link SectionRepository} over {@code organization_section}. */
public final class JooqSectionRepository implements SectionRepository {

    private static final Map<String, Field<?>> SORTABLE = Map.of(
            "name", ORGANIZATION_SECTION.NAME,
            "capacity", ORGANIZATION_SECTION.CAPACITY);

    private final DSLContext dsl;

    public JooqSectionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Section> findById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(ORGANIZATION_SECTION)
                .where(ORGANIZATION_SECTION.INSTITUTION_ID.eq(institutionId.value()))
                .and(ORGANIZATION_SECTION.ID.eq(id))
                .fetchOptional().map(JooqSectionRepository::toDomain);
    }

    @Override
    public void insert(InstitutionId institutionId, Section section) {
        OrganizationWrites.translating(() -> dsl.insertInto(ORGANIZATION_SECTION)
                .set(ORGANIZATION_SECTION.INSTITUTION_ID, institutionId.value())
                .set(ORGANIZATION_SECTION.ID, section.id())
                .set(ORGANIZATION_SECTION.ACADEMIC_YEAR_ID, section.academicYearId())
                .set(ORGANIZATION_SECTION.GRADE_ID, section.gradeId())
                .set(ORGANIZATION_SECTION.NAME, section.name())
                .set(ORGANIZATION_SECTION.CAPACITY, section.capacity())
                .set(ORGANIZATION_SECTION.IS_ACTIVE, section.active())
                .set(ORGANIZATION_SECTION.CREATED_AT, OrganizationWrites.toDatabase(section.createdAt()))
                .set(ORGANIZATION_SECTION.UPDATED_AT, OrganizationWrites.toDatabase(section.updatedAt()))
                .execute());
    }

    @Override
    public void update(InstitutionId institutionId, Section section) {
        OrganizationWrites.translating(() -> {
            int rows = dsl.update(ORGANIZATION_SECTION)
                    .set(ORGANIZATION_SECTION.NAME, section.name())
                    .set(ORGANIZATION_SECTION.CAPACITY, section.capacity())
                    .set(ORGANIZATION_SECTION.IS_ACTIVE, section.active())
                    .set(ORGANIZATION_SECTION.UPDATED_AT, OrganizationWrites.toDatabase(section.updatedAt()))
                    .where(ORGANIZATION_SECTION.INSTITUTION_ID.eq(institutionId.value()))
                    .and(ORGANIZATION_SECTION.ID.eq(section.id()))
                    .execute();
            if (rows == 0) {
                throw new ResourceNotFoundException();
            }
        });
    }

    @Override
    public Page<Section> list(InstitutionId institutionId, UUID academicYearId, UUID gradeId,
            PageRequest request) {
        Condition scope = ORGANIZATION_SECTION.INSTITUTION_ID.eq(institutionId.value());
        if (academicYearId != null) {
            scope = scope.and(ORGANIZATION_SECTION.ACADEMIC_YEAR_ID.eq(academicYearId));
        }
        if (gradeId != null) {
            scope = scope.and(ORGANIZATION_SECTION.GRADE_ID.eq(gradeId));
        }
        long total = dsl.fetchCount(ORGANIZATION_SECTION, scope);
        List<Section> rows = dsl.selectFrom(ORGANIZATION_SECTION).where(scope)
                .orderBy(order(request), ORGANIZATION_SECTION.ID.asc())
                .limit(request.size()).offset(request.offset())
                .fetch(JooqSectionRepository::toDomain);
        return new Page<>(rows, total, request.page(), request.size());
    }

    private static SortField<?> order(PageRequest request) {
        Field<?> field = SORTABLE.get(request.sortField());
        return request.ascending() ? field.asc() : field.desc();
    }

    private static Section toDomain(OrganizationSectionRecord row) {
        return new Section(row.getId(), row.getAcademicYearId(), row.getGradeId(), row.getName(),
                row.getCapacity(), row.getIsActive(),
                OrganizationWrites.fromDatabase(row.getCreatedAt()),
                OrganizationWrites.fromDatabase(row.getUpdatedAt()));
    }
}
