package com.confia.identity.application;

/**
 * The outcome of one issuance (password-recovery-token design.md decision 6). It never carries the
 * token, which leaves only through {@link PasswordResetLinkSender}.
 */
public enum IssuePasswordResetTokenDecision {

    /** A token was issued, every earlier open token superseded, and the link handed to the sender. */
    ISSUED,

    /** The account already had three issuances in the last sixty minutes: nothing issued or sent. */
    SKIPPED,

    /**
     * No account with that id under the task's institution. It cannot happen while no role may
     * delete an account; it is a result rather than an exception so a stale task ends quietly.
     */
    ACCOUNT_NOT_FOUND
}
