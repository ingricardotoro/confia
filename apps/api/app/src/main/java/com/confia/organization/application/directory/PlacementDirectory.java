package com.confia.organization.application.directory;

import com.confia.kernel.InstitutionId;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port, read-only, through which {@code charges} asks {@code organization} where the students of a charge plan are:
 * the sections that a plan target covers in an academic year, the grade and the modality of a section, and the time zone of
 * the institution (docs/cortes/charges-accrual.md). Every method is scoped to the institution given. It writes nothing and
 * takes no lock.
 */
public interface PlacementDirectory {

    /** What a plan targets: one modality, one grade or one section. */
    enum TargetScope { MODALITY, GRADE, SECTION }

    /**
     * The sections of the academic year that the target covers: every section of the modality, of the grade, or the section
     * itself. Empty (not absent) when a modality or a grade has no section in the year; absent when the target is a section
     * that does not belong to that year or does not exist, which is the plan target outside its year.
     */
    Optional<List<UUID>> sectionsOfTarget(InstitutionId institutionId, UUID academicYearId, TargetScope scope,
            UUID targetRefId);

    /** The grade and the modality of the sections, by section identifier, in one query; unknown sections are absent. */
    Map<UUID, SectionPlacement> placementsOf(InstitutionId institutionId, Collection<UUID> sectionIds);

    /** The time zone of the institution, or empty when it does not exist. */
    Optional<ZoneId> timezoneOf(InstitutionId institutionId);

    /** A section as a place of a student: its grade and the modality of that grade. */
    record SectionPlacement(UUID sectionId, UUID gradeId, UUID modalityId) {
    }
}
