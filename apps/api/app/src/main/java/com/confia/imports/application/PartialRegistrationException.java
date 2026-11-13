package com.confia.imports.application;

/**
 * Registering the rows of a file failed for a reason that is not a conflict of a row, after some rows were already
 * committed. It carries how many were created before the failure and the original failure as its cause, so the use case
 * can leave a trace of the partial result and then rethrow the cause. It carries no value of any row.
 */
final class PartialRegistrationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int created;

    PartialRegistrationException(int created, RuntimeException cause) {
        super("the import failed after " + created + " rows were created", cause);
        this.created = created;
    }

    int created() {
        return created;
    }

    RuntimeException failure() {
        return (RuntimeException) getCause();
    }
}
