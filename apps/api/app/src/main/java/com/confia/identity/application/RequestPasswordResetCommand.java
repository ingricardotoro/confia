package com.confia.identity.application;

import java.util.Objects;

/**
 * A password-reset request: only the presented identifier. It has no institution field, because
 * the institution comes from the process configuration and never from the caller, and no password
 * (password-recovery-token design.md decision 6).
 */
public record RequestPasswordResetCommand(String presentedIdentifier) {

    public RequestPasswordResetCommand {
        Objects.requireNonNull(presentedIdentifier, "presentedIdentifier");
    }
}
