package com.confia.guardians.application.importing;

/** What happened to the set of links of one student handed to {@link GuardianLinkImportGateway#applyAll}. */
public enum LinkOutcome {
    /** The links of the student were registered. */
    CREATED,
    /** The student already has current links (earlier or concurrent application, or by hand): nothing changed. */
    ALREADY_LINKED,
    /** The student is not active (it changed after the judgment). */
    STUDENT_NOT_ACTIVE,
    /** A guardian of the set is not active (it changed after the judgment). */
    GUARDIAN_NOT_ACTIVE
}
