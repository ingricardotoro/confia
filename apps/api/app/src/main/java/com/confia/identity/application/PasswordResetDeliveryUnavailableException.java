package com.confia.identity.application;

/**
 * Raised when the reset link cannot be delivered because no delivery adapter exists
 * (background-jobs-with-db-scheduler design.md decision 18, owner decision P4). Its message is fixed
 * and it carries no cause: it can never hold the token, the link or the address, so it is safe in a
 * log and in the failure record of a job.
 */
public final class PasswordResetDeliveryUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PasswordResetDeliveryUnavailableException() {
        super("password reset delivery is unavailable", null, false, false);
    }
}
