package com.confia.guardians.application;

import com.confia.guardians.domain.Guardian;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A guardian as the web layer may see it. The national identity document and the RTN are not here, nor can they
 * be: the view has only whether there is one.
 *
 * @param students the students linked to the guardian now (code, names, relationship, percentage), or
 *     {@code null} where the answer does not carry them (a list and a creation)
 */
public record GuardianView(UUID id, String guardianCode, String firstName, String lastName, boolean hasNationalId,
        boolean hasRtn, String email, String phone, String address, String preferredChannel, String status,
        Instant createdAt, Instant updatedAt, List<LinkedStudentView> students) {

    static GuardianView of(Guardian guardian, List<LinkedStudentView> students) {
        return new GuardianView(guardian.id(), guardian.guardianCode(), guardian.firstName(), guardian.lastName(),
                guardian.hasNationalId(), guardian.hasRtn(), guardian.email(), guardian.phone(), guardian.address(),
                guardian.preferredChannel().name(), guardian.status().name(), guardian.createdAt(),
                guardian.updatedAt(), students);
    }

    /** Personal data never reaches a log through this method. */
    @Override
    public String toString() {
        return "GuardianView[id=" + id + "]";
    }
}
