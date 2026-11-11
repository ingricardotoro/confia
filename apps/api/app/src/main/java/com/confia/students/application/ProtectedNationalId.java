package com.confia.students.application;

import java.util.Objects;

/**
 * A national identity document as it is stored: the encrypted column value and the blind index that
 * detects duplicates. Neither is the document, and neither is ever shown, logged or audited; the text
 * representation is redacted anyway.
 */
public record ProtectedNationalId(String encrypted, String blindIndex) {

    public ProtectedNationalId {
        Objects.requireNonNull(encrypted, "encrypted");
        Objects.requireNonNull(blindIndex, "blindIndex");
    }

    @Override
    public String toString() {
        return "ProtectedNationalId[REDACTED]";
    }
}
