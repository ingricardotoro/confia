package com.confia.organization.infrastructure;

import static confia.generated.jooq.tables.OrganizationGrade.ORGANIZATION_GRADE;
import static confia.generated.jooq.tables.OrganizationInstitution.ORGANIZATION_INSTITUTION;
import static confia.generated.jooq.tables.OrganizationSection.ORGANIZATION_SECTION;

import com.confia.kernel.InstitutionId;
import com.confia.organization.application.directory.PlacementDirectory;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The jOOQ adapter of {@link PlacementDirectory}: read-only queries over the sections and grades of the institution. It
 * never opens a transaction and never sets the institution of the session; row-level security does the second filter.
 */
public final class JooqPlacementDirectory implements PlacementDirectory {

    private final DSLContext dsl;

    public JooqPlacementDirectory(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<List<UUID>> sectionsOfTarget(InstitutionId institutionId, UUID academicYearId, TargetScope scope,
            UUID targetRefId) {
        UUID institution = institutionId.value();
        return switch (scope) {
            case SECTION -> dsl.fetchExists(ORGANIZATION_SECTION, ORGANIZATION_SECTION.INSTITUTION_ID.eq(institution)
                    .and(ORGANIZATION_SECTION.ID.eq(targetRefId))
                    .and(ORGANIZATION_SECTION.ACADEMIC_YEAR_ID.eq(academicYearId)))
                            ? Optional.of(List.of(targetRefId)) : Optional.empty();
            case GRADE -> Optional.of(dsl.select(ORGANIZATION_SECTION.ID).from(ORGANIZATION_SECTION)
                    .where(ORGANIZATION_SECTION.INSTITUTION_ID.eq(institution))
                    .and(ORGANIZATION_SECTION.ACADEMIC_YEAR_ID.eq(academicYearId))
                    .and(ORGANIZATION_SECTION.GRADE_ID.eq(targetRefId)).fetch(ORGANIZATION_SECTION.ID));
            case MODALITY -> Optional.of(dsl.select(ORGANIZATION_SECTION.ID).from(ORGANIZATION_SECTION)
                    .join(ORGANIZATION_GRADE).on(ORGANIZATION_GRADE.INSTITUTION_ID.eq(ORGANIZATION_SECTION.INSTITUTION_ID)
                            .and(ORGANIZATION_GRADE.ID.eq(ORGANIZATION_SECTION.GRADE_ID)))
                    .where(ORGANIZATION_SECTION.INSTITUTION_ID.eq(institution))
                    .and(ORGANIZATION_SECTION.ACADEMIC_YEAR_ID.eq(academicYearId))
                    .and(ORGANIZATION_GRADE.MODALITY_ID.eq(targetRefId)).fetch(ORGANIZATION_SECTION.ID));
        };
    }

    @Override
    public Map<UUID, SectionPlacement> placementsOf(InstitutionId institutionId, Collection<UUID> sectionIds) {
        Map<UUID, SectionPlacement> found = new HashMap<>();
        if (sectionIds.isEmpty()) {
            return found;
        }
        dsl.select(ORGANIZATION_SECTION.ID, ORGANIZATION_SECTION.GRADE_ID, ORGANIZATION_GRADE.MODALITY_ID)
                .from(ORGANIZATION_SECTION)
                .join(ORGANIZATION_GRADE).on(ORGANIZATION_GRADE.INSTITUTION_ID.eq(ORGANIZATION_SECTION.INSTITUTION_ID)
                        .and(ORGANIZATION_GRADE.ID.eq(ORGANIZATION_SECTION.GRADE_ID)))
                .where(ORGANIZATION_SECTION.INSTITUTION_ID.eq(institutionId.value()))
                .and(ORGANIZATION_SECTION.ID.in(sectionIds))
                .fetch().forEach(row -> found.put(row.get(ORGANIZATION_SECTION.ID),
                        new SectionPlacement(row.get(ORGANIZATION_SECTION.ID), row.get(ORGANIZATION_SECTION.GRADE_ID),
                                row.get(ORGANIZATION_GRADE.MODALITY_ID))));
        return found;
    }

    @Override
    public Optional<ZoneId> timezoneOf(InstitutionId institutionId) {
        return dsl.select(ORGANIZATION_INSTITUTION.TIMEZONE).from(ORGANIZATION_INSTITUTION)
                .where(ORGANIZATION_INSTITUTION.ID.eq(institutionId.value()))
                .fetchOptional(ORGANIZATION_INSTITUTION.TIMEZONE).map(ZoneId::of);
    }
}
