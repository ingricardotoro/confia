package com.confia.guardians.application.importing;

/**
 * A field of a row that cannot be imported and the stable reason: {@code required}, {@code invalid-format}, {@code
 * too-long}, {@code invalid-percent}, {@code duplicate-in-file}, {@code duplicate-national-id}, {@code
 * already-imported}, {@code unknown-student}, {@code unknown-guardian}, {@code student-not-active}, {@code
 * guardian-not-active}, {@code student-already-linked}, {@code group-rejected} or a rule of the set of links ({@code
 * sum-not-100}, {@code multiple-primary-payer}, {@code multiple-billing-recipient}, {@code duplicate-guardian}). It
 * carries the line, the name of the column ({@code row} when it is the whole row) and the reason, never a value.
 */
public record RowProblem(int line, String field, String reason) {
}
