package com.confia.imports.application;

import com.confia.imports.domain.ImportKind;
import com.confia.imports.domain.Rejection;
import com.confia.shared.security.AuthenticatedActor;
import java.util.List;

/**
 * What a kind of import adds to the flow that every kind shares (read the file, simulate, apply, count, record the
 * batch): how its rows are read from the text, judged and registered, through the port that the module owning the data
 * publishes. Nothing here keeps a value of the file; the answers are lines, columns and reasons.
 */
interface ImportProcessor {

    /** The kind this processor handles. */
    ImportKind kind();

    /**
     * Parses the text with the template of the kind (an {@code InvalidImportFileException} when the file cannot be
     * read as such an import) and has the owning module judge every row, writing nothing.
     */
    Judgement judge(AuthenticatedActor actor, String requestId, String text, int maxRows);

    /**
     * What judging a file found: the data rows it has, every rejection and skip (a row may appear more than once, once
     * per field, and a set of rows may be rejected whole) in no particular order, and what registers the rows that
     * had no problem.
     */
    record Judgement(int totalRows, List<Rejection> rejections, Registrar registrar) {

        public Judgement {
            rejections = List.copyOf(rejections);
        }
    }

    /** Registers the valid rows of one judgment through the owning module. */
    @FunctionalInterface
    interface Registrar {

        /**
         * Registers the valid rows. A failure that is not a conflict of a row, after some rows were committed, is a
         * {@link PartialRegistrationException}.
         */
        Registration register();
    }

    /**
     * The result of registering: the rows created, and the rows that were not, which turned out to be skipped (the
     * thing was already there) or rejected (a conflict or a change after the judgment), each as a rejection.
     */
    record Registration(int created, List<Rejection> late) {

        public Registration {
            late = List.copyOf(late);
        }
    }
}
