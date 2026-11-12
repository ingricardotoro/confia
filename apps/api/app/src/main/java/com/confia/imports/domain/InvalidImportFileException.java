package com.confia.imports.domain;

import com.confia.kernel.DomainException;
import java.util.List;

/**
 * The file cannot be read as a student import at all: it is empty, too big, not UTF-8, has too many rows, is not a
 * well-formed CSV, or its header lacks a required column or has one that is not part of the template. Published to
 * clients as {@code validation-failed} with the list of {@link Problem}; each names a field of the template (or a
 * position, never the text of the header) and a stable reason, and never a value of the file.
 */
public final class InvalidImportFileException extends DomainException {

    public static final String CODE = "validation-failed";

    /** The field ({@code file}, a column of the template or {@code columns[n]}) and the reason. */
    public record Problem(String field, String reason) {
    }

    private final transient List<Problem> problems;

    public InvalidImportFileException(String field, String reason) {
        this(List.of(new Problem(field, reason)));
    }

    public InvalidImportFileException(List<Problem> problems) {
        super(CODE, "the import file is not valid");
        this.problems = List.copyOf(problems);
    }

    public List<Problem> problems() {
        return problems;
    }
}
