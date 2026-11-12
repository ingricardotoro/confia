package com.confia.students.application.importing;

/** What happened to a row handed to {@link StudentImportGateway#createAll}. */
public enum CreateOutcome {
    /** The student was registered. */
    CREATED,
    /** A student with the same reference already exists (an earlier or a concurrent application of the file). */
    ALREADY_IMPORTED,
    /** Another student of the institution holds the same national identity document. */
    DUPLICATE_NATIONAL_ID
}
