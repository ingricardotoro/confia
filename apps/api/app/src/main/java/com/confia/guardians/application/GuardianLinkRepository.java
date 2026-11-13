package com.confia.guardians.application;

import com.confia.guardians.domain.GuardianLink;
import com.confia.kernel.InstitutionId;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Output port of the links between guardians and students. Links are only inserted, read and closed: there is no
 * operation that updates anything but the end of a current link and none that deletes. A second current link of
 * the same guardian and student, a second primary payer and a second billing recipient are refused by the
 * database, as the last word behind the lock that serializes the replacements of a student.
 */
public interface GuardianLinkRepository {

    /** The current links of the student (not closed), the oldest first. */
    List<GuardianLink> currentOfStudent(InstitutionId institutionId, UUID studentId);

    /** The current links of the guardian, the oldest first. */
    List<GuardianLink> currentOfGuardian(InstitutionId institutionId, UUID guardianId);

    /** Which of these students have at least one current link, in one query. */
    Set<UUID> studentIdsWithCurrentLinks(InstitutionId institutionId, Collection<UUID> studentIds);

    /** Every link of the student, closed ones included, the oldest first (for tests and audit reading). */
    List<GuardianLink> historyOfStudent(InstitutionId institutionId, UUID studentId);

    void insertAll(InstitutionId institutionId, Collection<GuardianLink> links);

    /** Closes the current links with these identifiers on {@code on}; a link already closed is never touched. */
    void close(InstitutionId institutionId, Collection<UUID> linkIds, LocalDate on);
}
