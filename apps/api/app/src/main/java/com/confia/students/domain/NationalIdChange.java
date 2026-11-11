package com.confia.students.domain;

/**
 * What an edit asks of the national identity document: keep the one that is stored (the value was
 * omitted), remove it (the value was empty) or replace it with a new one.
 */
public sealed interface NationalIdChange {

    /** Omitted value: the stored document stays as it is. */
    enum Keep implements NationalIdChange {
        INSTANCE
    }

    /** Empty value: the stored document is removed. */
    enum Remove implements NationalIdChange {
        INSTANCE
    }

    /** A new document, already validated. */
    record Replace(NationalId nationalId) implements NationalIdChange {
    }

    /** A null keeps, a blank removes, anything else must be a valid document and replaces. */
    static NationalIdChange of(String raw) {
        if (raw == null) {
            return Keep.INSTANCE;
        }
        if (raw.isBlank()) {
            return Remove.INSTANCE;
        }
        return new Replace(NationalId.of(raw));
    }
}
