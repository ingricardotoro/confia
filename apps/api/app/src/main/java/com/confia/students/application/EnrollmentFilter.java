package com.confia.students.application;

import com.confia.students.domain.EnrollmentStatus;
import java.util.Collection;
import java.util.UUID;

/**
 * What a list of enrollments keeps.
 *
 * @param academicYearId only this academic year, or {@code null} for every year
 * @param sectionIds only these sections, or {@code null} for every section; an empty collection keeps nothing
 * @param status only this status, or {@code null}
 */
public record EnrollmentFilter(UUID academicYearId, Collection<UUID> sectionIds, EnrollmentStatus status) {
}
