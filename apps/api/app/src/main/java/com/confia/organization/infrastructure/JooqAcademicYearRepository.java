package com.confia.organization.infrastructure;

import static confia.generated.jooq.tables.OrganizationAcademicYear.ORGANIZATION_ACADEMIC_YEAR;
import static confia.generated.jooq.tables.OrganizationInstitution.ORGANIZATION_INSTITUTION;

import com.confia.kernel.InstitutionId;
import com.confia.organization.application.AcademicYearRepository;
import com.confia.organization.application.Page;
import com.confia.organization.application.PageRequest;
import com.confia.organization.domain.AcademicYear;
import com.confia.organization.domain.AcademicYearStatus;
import com.confia.organization.domain.ResourceNotFoundException;
import confia.generated.jooq.tables.records.OrganizationAcademicYearRecord;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;

/**
 * The jOOQ adapter of {@link AcademicYearRepository} over {@code organization_academic_year}. It never
 * opens a transaction (it runs inside the one of {@code TransactionRunner}, ADR-0015 rule 7) and never
 * sets the institution of the session; row-level security does the second filter on every statement.
 */
public final class JooqAcademicYearRepository implements AcademicYearRepository {

    /** The sortable fields of the list, by the name the API gives them. */
    private static final Map<String, Field<?>> SORTABLE = Map.of(
            "name", ORGANIZATION_ACADEMIC_YEAR.NAME,
            "startDate", ORGANIZATION_ACADEMIC_YEAR.START_DATE,
            "endDate", ORGANIZATION_ACADEMIC_YEAR.END_DATE,
            "status", ORGANIZATION_ACADEMIC_YEAR.STATUS);

    private final DSLContext dsl;

    public JooqAcademicYearRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * {@code SELECT ... FOR UPDATE} on the row of the institution: every writer of the ranges of one
     * institution queues here, so the overlap check that follows sees what the one before committed.
     */
    @Override
    public void lockInstitution(InstitutionId institutionId) {
        dsl.select(ORGANIZATION_INSTITUTION.ID).from(ORGANIZATION_INSTITUTION)
                .where(ORGANIZATION_INSTITUTION.ID.eq(institutionId.value()))
                .forUpdate().fetch();
    }

    @Override
    public Optional<AcademicYear> findById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(ORGANIZATION_ACADEMIC_YEAR)
                .where(ORGANIZATION_ACADEMIC_YEAR.INSTITUTION_ID.eq(institutionId.value()))
                .and(ORGANIZATION_ACADEMIC_YEAR.ID.eq(id))
                .fetchOptional().map(JooqAcademicYearRepository::toDomain);
    }

    @Override
    public Optional<AcademicYear> findActive(InstitutionId institutionId) {
        return dsl.selectFrom(ORGANIZATION_ACADEMIC_YEAR)
                .where(ORGANIZATION_ACADEMIC_YEAR.INSTITUTION_ID.eq(institutionId.value()))
                .and(ORGANIZATION_ACADEMIC_YEAR.STATUS.eq(AcademicYearStatus.ACTIVE.name()))
                .fetchOptional().map(JooqAcademicYearRepository::toDomain);
    }

    @Override
    public List<AcademicYear> findOverlapping(InstitutionId institutionId, LocalDate start,
            LocalDate end) {
        return dsl.selectFrom(ORGANIZATION_ACADEMIC_YEAR)
                .where(ORGANIZATION_ACADEMIC_YEAR.INSTITUTION_ID.eq(institutionId.value()))
                .and(ORGANIZATION_ACADEMIC_YEAR.START_DATE.le(end))
                .and(ORGANIZATION_ACADEMIC_YEAR.END_DATE.ge(start))
                .fetch(JooqAcademicYearRepository::toDomain);
    }

    @Override
    public void insert(InstitutionId institutionId, AcademicYear year) {
        OrganizationWrites.translating(() -> dsl.insertInto(ORGANIZATION_ACADEMIC_YEAR)
                .set(ORGANIZATION_ACADEMIC_YEAR.INSTITUTION_ID, institutionId.value())
                .set(ORGANIZATION_ACADEMIC_YEAR.ID, year.id())
                .set(ORGANIZATION_ACADEMIC_YEAR.NAME, year.name())
                .set(ORGANIZATION_ACADEMIC_YEAR.START_DATE, year.startDate())
                .set(ORGANIZATION_ACADEMIC_YEAR.END_DATE, year.endDate())
                .set(ORGANIZATION_ACADEMIC_YEAR.STATUS, year.status().name())
                .set(ORGANIZATION_ACADEMIC_YEAR.IS_ACTIVE, year.active())
                .set(ORGANIZATION_ACADEMIC_YEAR.CREATED_AT, OrganizationWrites.toDatabase(year.createdAt()))
                .set(ORGANIZATION_ACADEMIC_YEAR.UPDATED_AT, OrganizationWrites.toDatabase(year.updatedAt()))
                .execute());
    }

    @Override
    public void update(InstitutionId institutionId, AcademicYear year) {
        OrganizationWrites.translating(() -> {
            int rows = dsl.update(ORGANIZATION_ACADEMIC_YEAR)
                    .set(ORGANIZATION_ACADEMIC_YEAR.NAME, year.name())
                    .set(ORGANIZATION_ACADEMIC_YEAR.START_DATE, year.startDate())
                    .set(ORGANIZATION_ACADEMIC_YEAR.END_DATE, year.endDate())
                    .set(ORGANIZATION_ACADEMIC_YEAR.STATUS, year.status().name())
                    .set(ORGANIZATION_ACADEMIC_YEAR.IS_ACTIVE, year.active())
                    .set(ORGANIZATION_ACADEMIC_YEAR.UPDATED_AT, OrganizationWrites.toDatabase(year.updatedAt()))
                    .where(ORGANIZATION_ACADEMIC_YEAR.INSTITUTION_ID.eq(institutionId.value()))
                    .and(ORGANIZATION_ACADEMIC_YEAR.ID.eq(year.id()))
                    .execute();
            if (rows == 0) {
                throw new ResourceNotFoundException();
            }
        });
    }

    @Override
    public Page<AcademicYear> list(InstitutionId institutionId, PageRequest request) {
        var scope = ORGANIZATION_ACADEMIC_YEAR.INSTITUTION_ID.eq(institutionId.value());
        long total = dsl.fetchCount(ORGANIZATION_ACADEMIC_YEAR, scope);
        List<AcademicYear> rows = dsl.selectFrom(ORGANIZATION_ACADEMIC_YEAR).where(scope)
                .orderBy(order(request), ORGANIZATION_ACADEMIC_YEAR.ID.asc())
                .limit(request.size()).offset(request.offset())
                .fetch(JooqAcademicYearRepository::toDomain);
        return new Page<>(rows, total, request.page(), request.size());
    }

    private static SortField<?> order(PageRequest request) {
        Field<?> field = SORTABLE.get(request.sortField());
        return request.ascending() ? field.asc() : field.desc();
    }

    private static AcademicYear toDomain(OrganizationAcademicYearRecord row) {
        return new AcademicYear(row.getId(), row.getName(), row.getStartDate(), row.getEndDate(),
                AcademicYearStatus.valueOf(row.getStatus()), row.getIsActive(),
                OrganizationWrites.fromDatabase(row.getCreatedAt()),
                OrganizationWrites.fromDatabase(row.getUpdatedAt()));
    }
}
