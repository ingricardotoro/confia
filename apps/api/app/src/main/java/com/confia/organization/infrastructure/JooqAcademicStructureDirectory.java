package com.confia.organization.infrastructure;

import static confia.generated.jooq.tables.OrganizationAcademicYear.ORGANIZATION_ACADEMIC_YEAR;
import static confia.generated.jooq.tables.OrganizationGrade.ORGANIZATION_GRADE;
import static confia.generated.jooq.tables.OrganizationModality.ORGANIZATION_MODALITY;

import com.confia.kernel.InstitutionId;
import com.confia.organization.application.directory.AcademicStructureDirectory;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The jOOQ adapter of {@link AcademicStructureDirectory}: one existence query per piece, read-only. It never opens a
 * transaction and never sets the institution of the session; row-level security does the second filter on every
 * statement.
 */
public final class JooqAcademicStructureDirectory implements AcademicStructureDirectory {

    private final DSLContext dsl;

    public JooqAcademicStructureDirectory(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean academicYearExists(InstitutionId institutionId, UUID academicYearId) {
        return dsl.fetchExists(ORGANIZATION_ACADEMIC_YEAR, ORGANIZATION_ACADEMIC_YEAR.INSTITUTION_ID
                .eq(institutionId.value()).and(ORGANIZATION_ACADEMIC_YEAR.ID.eq(academicYearId)));
    }

    @Override
    public boolean modalityExists(InstitutionId institutionId, UUID modalityId) {
        return dsl.fetchExists(ORGANIZATION_MODALITY, ORGANIZATION_MODALITY.INSTITUTION_ID
                .eq(institutionId.value()).and(ORGANIZATION_MODALITY.ID.eq(modalityId)));
    }

    @Override
    public boolean gradeExists(InstitutionId institutionId, UUID gradeId) {
        return dsl.fetchExists(ORGANIZATION_GRADE, ORGANIZATION_GRADE.INSTITUTION_ID
                .eq(institutionId.value()).and(ORGANIZATION_GRADE.ID.eq(gradeId)));
    }
}
