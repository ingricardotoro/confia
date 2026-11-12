package com.confia.students.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One entry of the history of the sections of an enrollment, with the names of both sections. */
public record TransferView(UUID id, UUID fromSectionId, String fromSectionName, UUID toSectionId,
        String toSectionName, LocalDate transferredOn, String reason, UUID transferredBy, Instant createdAt) {

    /** The reason is free text typed by the staff: it never reaches a log through this method. */
    @Override
    public String toString() {
        return "TransferView[id=" + id + "]";
    }
}
