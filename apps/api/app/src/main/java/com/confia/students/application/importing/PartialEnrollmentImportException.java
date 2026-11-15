package com.confia.students.application.importing;

/**
 * {@link EnrollmentImportGateway#applyAll} failed for a reason that is not a conflict of a row, after some enrollments
 * were already committed. It carries how many were created before the failure and the original failure as its cause,
 * so the caller can leave a trace of the partial result and then rethrow the cause. It carries no value of any row; its
 * own message is fixed.
 */
public final class PartialEnrollmentImportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int created;

    public PartialEnrollmentImportException(int created, RuntimeException cause) {
        super("the import failed after " + created + " enrollments were created", cause);
        this.created = created;
    }

    /** The enrollments committed before the failure. */
    public int created() {
        return created;
    }

    /** The failure that stopped the import. */
    public RuntimeException failure() {
        return (RuntimeException) getCause();
    }
}
