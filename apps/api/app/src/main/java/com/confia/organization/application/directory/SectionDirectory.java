package com.confia.organization.application.directory;

import com.confia.kernel.InstitutionId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port, read-only, through which other modules ask {@code organization} about sections and academic
 * years. Every method is scoped to the institution given, and a section of another institution is never
 * found. Nothing here writes: the one operation with an effect, {@link #lock}, only takes a row lock that
 * lasts until the end of the transaction.
 */
public interface SectionDirectory {

    /** The section, or empty when it does not exist in the institution. */
    Optional<SectionRef> find(InstitutionId institutionId, UUID sectionId);

    /**
     * The section, locked until the end of the transaction ({@code SELECT ... FOR UPDATE} on its row), so two
     * transactions that decide on its capacity take turns. Empty when it does not exist in the institution.
     */
    Optional<SectionRef> lock(InstitutionId institutionId, UUID sectionId);

    /** The sections with these identifiers, by identifier, in one query; unknown identifiers are absent. */
    Map<UUID, SectionRef> findAll(InstitutionId institutionId, Collection<UUID> sectionIds);

    /**
     * The identifiers of the sections of a grade.
     *
     * @param academicYearId only the sections of this year, or {@code null} for every year
     */
    List<UUID> sectionIdsOfGrade(InstitutionId institutionId, UUID academicYearId, UUID gradeId);

    /**
     * The sections that go by these names (see {@link SectionNames}), by names, in one query; names nobody goes by are
     * absent and names that more than one section goes by list all of them, so the caller can tell them apart.
     */
    Map<SectionNames, List<SectionRef>> findByNames(InstitutionId institutionId, Collection<SectionNames> names);

    /** Whether the academic year exists in the institution (a year of another institution is not found). */
    boolean academicYearExists(InstitutionId institutionId, UUID academicYearId);

    /** The identifier of the academic year whose status is {@code ACTIVE}, or empty when there is none. */
    Optional<UUID> activeAcademicYearId(InstitutionId institutionId);
}
