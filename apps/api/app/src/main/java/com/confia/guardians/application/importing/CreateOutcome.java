package com.confia.guardians.application.importing;

/** What happened to a row handed to {@link GuardianImportGateway#createAll}. */
public enum CreateOutcome {
    /** The guardian was registered. */
    CREATED,
    /** A guardian with the same reference already exists (an earlier or a concurrent application of the file). */
    ALREADY_IMPORTED,
    /** Another guardian of the institution holds the same national identity document. */
    DUPLICATE_NATIONAL_ID
}
