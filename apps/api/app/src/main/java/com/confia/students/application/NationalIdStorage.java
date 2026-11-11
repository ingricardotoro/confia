package com.confia.students.application;

import java.util.Objects;

/** What an update does to the stored document: leave it, store a new protected one, or clear it. */
public sealed interface NationalIdStorage {

    /** The stored document is left exactly as it is. */
    enum Keep implements NationalIdStorage {
        INSTANCE
    }

    /** The stored document is removed. */
    enum Clear implements NationalIdStorage {
        INSTANCE
    }

    /** The stored document is replaced. */
    record Store(ProtectedNationalId document) implements NationalIdStorage {

        public Store {
            Objects.requireNonNull(document, "document");
        }
    }
}
