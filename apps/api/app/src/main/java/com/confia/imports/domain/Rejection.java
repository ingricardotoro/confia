package com.confia.imports.domain;

/**
 * A row, or a field of a row, that is not imported: the line of the file, the name of the column and a stable
 * reason. It never carries the value that was rejected.
 */
public record Rejection(int line, String field, String reason) {

    /** The reason of a row that was imported before; the row is skipped, not a defect of the file. */
    public static final String ALREADY_IMPORTED = "already-imported";

    public boolean isAlreadyImported() {
        return ALREADY_IMPORTED.equals(reason);
    }
}
