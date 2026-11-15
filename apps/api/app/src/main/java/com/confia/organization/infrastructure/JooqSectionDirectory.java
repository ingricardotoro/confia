package com.confia.organization.infrastructure;

import static confia.generated.jooq.tables.OrganizationAcademicYear.ORGANIZATION_ACADEMIC_YEAR;
import static confia.generated.jooq.tables.OrganizationGrade.ORGANIZATION_GRADE;
import static confia.generated.jooq.tables.OrganizationModality.ORGANIZATION_MODALITY;
import static confia.generated.jooq.tables.OrganizationSection.ORGANIZATION_SECTION;

import com.confia.kernel.InstitutionId;
import com.confia.organization.application.directory.SectionDirectory;
import com.confia.organization.application.directory.SectionNames;
import com.confia.organization.application.directory.SectionRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectOnConditionStep;

/**
 * The jOOQ adapter of {@link SectionDirectory}: a section joined to its academic year, its grade and the
 * modality of the grade, read-only. The lock is {@code FOR UPDATE OF} the section row alone, so the year, the
 * grade and the modality stay free. It never opens a transaction and never sets the institution of the
 * session; row-level security does the second filter on every statement.
 */
public final class JooqSectionDirectory implements SectionDirectory {

    private static final String ACTIVE = "ACTIVE";

    private final DSLContext dsl;

    public JooqSectionDirectory(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<SectionRef> find(InstitutionId institutionId, UUID sectionId) {
        return joined().where(inInstitution(institutionId)).and(ORGANIZATION_SECTION.ID.eq(sectionId))
                .fetchOptional().map(JooqSectionDirectory::toRef);
    }

    @Override
    public Optional<SectionRef> lock(InstitutionId institutionId, UUID sectionId) {
        return joined().where(inInstitution(institutionId)).and(ORGANIZATION_SECTION.ID.eq(sectionId))
                .forUpdate().of(ORGANIZATION_SECTION)
                .fetchOptional().map(JooqSectionDirectory::toRef);
    }

    @Override
    public Map<UUID, SectionRef> findAll(InstitutionId institutionId, Collection<UUID> sectionIds) {
        Map<UUID, SectionRef> found = new HashMap<>();
        if (sectionIds.isEmpty()) {
            return found;
        }
        joined().where(inInstitution(institutionId)).and(ORGANIZATION_SECTION.ID.in(sectionIds))
                .fetch().forEach(row -> {
                    SectionRef ref = toRef(row);
                    found.put(ref.sectionId(), ref);
                });
        return found;
    }

    @Override
    public Map<SectionNames, List<SectionRef>> findByNames(InstitutionId institutionId,
            Collection<SectionNames> names) {
        Map<SectionNames, List<SectionRef>> found = new HashMap<>();
        if (names.isEmpty()) {
            return found;
        }
        // The names are compared in Java, with the same normalization the request went through, and not in SQL: the
        // sections of an institution are a few hundred at most, and a comparison in the database would depend on its
        // collation for accents and capitals.
        Set<SectionNames> wanted = Set.copyOf(names);
        joined().where(inInstitution(institutionId)).fetch().forEach(row -> {
            SectionRef ref = toRef(row);
            SectionNames key = new SectionNames(ref.academicYearName(), ref.modalityName(), ref.gradeName(),
                    ref.name());
            if (wanted.contains(key)) {
                found.computeIfAbsent(key, k -> new ArrayList<>()).add(ref);
            }
        });
        return found;
    }

    @Override
    public List<UUID> sectionIdsOfGrade(InstitutionId institutionId, UUID academicYearId, UUID gradeId) {
        Condition scope = ORGANIZATION_SECTION.INSTITUTION_ID.eq(institutionId.value())
                .and(ORGANIZATION_SECTION.GRADE_ID.eq(gradeId));
        if (academicYearId != null) {
            scope = scope.and(ORGANIZATION_SECTION.ACADEMIC_YEAR_ID.eq(academicYearId));
        }
        return dsl.select(ORGANIZATION_SECTION.ID).from(ORGANIZATION_SECTION).where(scope)
                .fetch(ORGANIZATION_SECTION.ID);
    }

    @Override
    public boolean academicYearExists(InstitutionId institutionId, UUID academicYearId) {
        return dsl.fetchExists(ORGANIZATION_ACADEMIC_YEAR, ORGANIZATION_ACADEMIC_YEAR.INSTITUTION_ID
                .eq(institutionId.value()).and(ORGANIZATION_ACADEMIC_YEAR.ID.eq(academicYearId)));
    }

    @Override
    public Optional<UUID> activeAcademicYearId(InstitutionId institutionId) {
        return dsl.select(ORGANIZATION_ACADEMIC_YEAR.ID).from(ORGANIZATION_ACADEMIC_YEAR)
                .where(ORGANIZATION_ACADEMIC_YEAR.INSTITUTION_ID.eq(institutionId.value()))
                .and(ORGANIZATION_ACADEMIC_YEAR.STATUS.eq(ACTIVE))
                .fetchOptional(ORGANIZATION_ACADEMIC_YEAR.ID);
    }

    private static Condition inInstitution(InstitutionId institutionId) {
        return ORGANIZATION_SECTION.INSTITUTION_ID.eq(institutionId.value());
    }

    private SelectOnConditionStep<Record> joined() {
        return dsl.select(List.of(ORGANIZATION_SECTION.ID, ORGANIZATION_SECTION.NAME,
                        ORGANIZATION_SECTION.IS_ACTIVE, ORGANIZATION_SECTION.CAPACITY,
                        ORGANIZATION_ACADEMIC_YEAR.ID, ORGANIZATION_ACADEMIC_YEAR.NAME,
                        ORGANIZATION_ACADEMIC_YEAR.STATUS, ORGANIZATION_ACADEMIC_YEAR.START_DATE,
                        ORGANIZATION_ACADEMIC_YEAR.END_DATE, ORGANIZATION_GRADE.ID, ORGANIZATION_GRADE.NAME,
                        ORGANIZATION_MODALITY.NAME))
                .from(ORGANIZATION_SECTION)
                .join(ORGANIZATION_ACADEMIC_YEAR)
                .on(ORGANIZATION_ACADEMIC_YEAR.INSTITUTION_ID.eq(ORGANIZATION_SECTION.INSTITUTION_ID)
                        .and(ORGANIZATION_ACADEMIC_YEAR.ID.eq(ORGANIZATION_SECTION.ACADEMIC_YEAR_ID)))
                .join(ORGANIZATION_GRADE)
                .on(ORGANIZATION_GRADE.INSTITUTION_ID.eq(ORGANIZATION_SECTION.INSTITUTION_ID)
                        .and(ORGANIZATION_GRADE.ID.eq(ORGANIZATION_SECTION.GRADE_ID)))
                .join(ORGANIZATION_MODALITY)
                .on(ORGANIZATION_MODALITY.INSTITUTION_ID.eq(ORGANIZATION_GRADE.INSTITUTION_ID)
                        .and(ORGANIZATION_MODALITY.ID.eq(ORGANIZATION_GRADE.MODALITY_ID)));
    }

    private static SectionRef toRef(Record row) {
        return new SectionRef(row.get(ORGANIZATION_SECTION.ID), row.get(ORGANIZATION_SECTION.NAME),
                row.get(ORGANIZATION_SECTION.IS_ACTIVE), row.get(ORGANIZATION_SECTION.CAPACITY),
                row.get(ORGANIZATION_ACADEMIC_YEAR.ID), row.get(ORGANIZATION_ACADEMIC_YEAR.NAME),
                row.get(ORGANIZATION_ACADEMIC_YEAR.STATUS), row.get(ORGANIZATION_ACADEMIC_YEAR.START_DATE),
                row.get(ORGANIZATION_ACADEMIC_YEAR.END_DATE), row.get(ORGANIZATION_GRADE.ID),
                row.get(ORGANIZATION_GRADE.NAME), row.get(ORGANIZATION_MODALITY.NAME));
    }
}
