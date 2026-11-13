package com.confia.imports.application;

/** The name of a kind of import that does not exist (the filter of the history). */
public final class UnknownImportKindException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnknownImportKindException() {
        super("unknown import kind");
    }
}
