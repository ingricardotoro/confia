package com.confia.imports.domain;

import java.util.Set;

/**
 * A row, or a field of a row, that is not imported: the line of the file, the name of the column and a stable
 * reason. It never carries the value that was rejected.
 */
public record Rejection(int line, String field, String reason) {

    /** The reason of a row that was imported before; the row is skipped, not a defect of the file. */
    public static final String ALREADY_IMPORTED = "already-imported";

    /**
     * The reason of the lines of a student that already has current links (made by hand or by an earlier application
     * of the same file); the import never replaces them, so the lines are skipped, not a defect of the file.
     */
    public static final String STUDENT_ALREADY_LINKED = "student-already-linked";

    private static final Set<String> SKIPPED = Set.of(ALREADY_IMPORTED, STUDENT_ALREADY_LINKED);

    /** Whether the reason is one of a row that is skipped because what it carries is already there. */
    public boolean isSkipped() {
        return SKIPPED.contains(reason);
    }
}
