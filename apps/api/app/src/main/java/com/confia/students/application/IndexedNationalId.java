package com.confia.students.application;

import java.util.Objects;
import java.util.UUID;

/** A stored encrypted document that a re-index has to recompute. Redacted: it holds the ciphertext. */
public record IndexedNationalId(UUID studentId, String encrypted) {

    public IndexedNationalId {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(encrypted, "encrypted");
    }

    @Override
    public String toString() {
        return "IndexedNationalId[REDACTED]";
    }
}
