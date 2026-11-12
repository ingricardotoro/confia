package com.confia.students.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry of the history of the sections an enrollment has been in. It is written once and never edited
 * or deleted (the database grants no {@code UPDATE} nor {@code DELETE} on it).
 *
 * @param reason why, or {@code null} when none was given
 * @param actorId the staff account that did it
 */
public record EnrollmentTransfer(UUID id, UUID enrollmentId, UUID fromSectionId, UUID toSectionId,
        LocalDate transferredOn, String reason, UUID actorId, Instant createdAt) {

    public EnrollmentTransfer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(enrollmentId, "enrollmentId");
        Objects.requireNonNull(fromSectionId, "fromSectionId");
        Objects.requireNonNull(toSectionId, "toSectionId");
        Objects.requireNonNull(transferredOn, "transferredOn");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** The reason is free text typed by the staff: it never reaches a log through this method. */
    @Override
    public String toString() {
        return "EnrollmentTransfer[id=" + id + "]";
    }
}
