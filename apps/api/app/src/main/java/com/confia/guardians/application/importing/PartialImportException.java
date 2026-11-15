package com.confia.guardians.application.importing;

/**
 * An import failed for a reason that is not a conflict of a row, after some rows were already committed. It carries how
 * many rows were created before the failure and the original failure as its cause, so the caller can leave a trace of
 * the partial result and then rethrow the cause. It carries no value of any row; its own message is fixed.
 */
public final class PartialImportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int created;

    public PartialImportException(int created, RuntimeException cause) {
        super("the import failed after " + created + " rows were created", cause);
        this.created = created;
    }

    /** The rows committed before the failure. */
    public int created() {
        return created;
    }

    /** The failure that stopped the import. */
    public RuntimeException failure() {
        return (RuntimeException) getCause();
    }
}
