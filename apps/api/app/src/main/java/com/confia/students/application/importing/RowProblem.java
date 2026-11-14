package com.confia.students.application.importing;

/**
 * A field of a row that cannot be imported and the stable reason: {@code required}, {@code invalid-format}, {@code
 * too-long}, {@code invalid-date}, {@code future-birth-date}, {@code duplicate-in-file}, {@code duplicate-national-id}
 * or {@code already-imported}. It carries the line, the name of the column and the reason, and never a value.
 */
public record RowProblem(int line, String field, String reason) {
}
