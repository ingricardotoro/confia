package com.confia.students.application;

import java.util.List;
import java.util.Objects;

/**
 * A national identity document as it is stored: the encrypted column value and the blind index (under the
 * current key version) that detects duplicates. {@code lookupIndexes} are the indexes of the same document
 * under every key version still configured, the current one first: during a key rotation a row may still
 * carry the index of the previous version, so a duplicate is searched under all of them. Nothing here is
 * the document, and nothing is ever shown, logged or audited; the text representation is redacted anyway.
 */
public record ProtectedNationalId(String encrypted, String blindIndex, List<String> lookupIndexes) {

    public ProtectedNationalId {
        Objects.requireNonNull(encrypted, "encrypted");
        Objects.requireNonNull(blindIndex, "blindIndex");
        Objects.requireNonNull(lookupIndexes, "lookupIndexes");
        lookupIndexes = List.copyOf(lookupIndexes);
        if (!lookupIndexes.contains(blindIndex)) {
            throw new IllegalArgumentException("the lookup indexes include the current blind index");
        }
    }

    /** A document with a single key version: the only index to look under is its own. */
    public ProtectedNationalId(String encrypted, String blindIndex) {
        this(encrypted, blindIndex, List.of(blindIndex));
    }

    @Override
    public String toString() {
        return "ProtectedNationalId[REDACTED]";
    }
}
