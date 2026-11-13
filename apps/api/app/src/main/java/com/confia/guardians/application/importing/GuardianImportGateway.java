package com.confia.guardians.application.importing;

import com.confia.shared.security.AuthenticatedActor;
import java.util.List;

/**
 * Port through which {@code imports} asks {@code guardians} to judge and to register the guardians of a file. Both
 * operations are scoped to the institution of the actor; the registration goes through the same use case as the hand
 * registration, so the code, the encrypted document and RTN with the blind index and the audit entry are exactly those.
 * Nothing here returns or logs a cell value.
 */
public interface GuardianImportGateway {

    /**
     * Judges every row, writing nothing: the rules of the registry on each field, repeated references or documents
     * within the rows and a reference that the institution already has ({@code already-imported}). It never consults
     * the documents already stored: whether a document is held by another guardian is only learned by
     * {@link #createAll}, as {@link CreateOutcome#DUPLICATE_NATIONAL_ID}. The problems come in line order.
     */
    List<RowProblem> evaluate(AuthenticatedActor actor, String requestId, List<GuardianImportRow> rows);

    /**
     * Registers rows that {@link #evaluate} found without problems, in blocks that commit together; a block that fails
     * because of a conflict is retried row by row so that one conflict never discards the others. The answer has one
     * outcome per row, in the order of {@code rows}.
     */
    List<CreateOutcome> createAll(AuthenticatedActor actor, String requestId, List<GuardianImportRow> rows);
}
