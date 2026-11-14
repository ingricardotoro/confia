package com.confia.imports.application;

import java.util.List;

/**
 * The file cannot be read as a student import (see {@code InvalidImportFileException}), as the web layer may see it:
 * the fields of the template, or the positions of columns, and the stable reasons; never a value of the file.
 */
public final class RejectedImportFileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** A field ({@code file}, a column of the template or {@code columns[n]}) and the reason. */
    public record FileProblem(String field, String reason) {
    }

    private final transient List<FileProblem> problems;

    public RejectedImportFileException(List<FileProblem> problems) {
        super("the import file is not valid");
        this.problems = List.copyOf(problems);
    }

    public List<FileProblem> problems() {
        return problems;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
