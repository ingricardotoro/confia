package com.confia.imports.application;

/** The history of a kind was asked for by an account that cannot read it; {@code students} says which permission is missing. */
public final class KindNotReadableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean students;

    public KindNotReadableException(boolean students) {
        super("import kind not readable");
        this.students = students;
    }

    /** True when the kind needs the permission to read students, false when it needs the one to read guardians. */
    public boolean students() {
        return students;
    }
}
