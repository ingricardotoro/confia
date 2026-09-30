package com.confia.identity.application;

import java.util.Objects;

/**
 * A password reset: the presented token, the new password in raw form and the second factor, if
 * any (password-recovery-token design.md decision 7). It has no institution field, because the
 * institution comes from the process configuration and never from the caller.
 *
 * <p><b>A final class with a redacted {@code toString()}, never a {@code record}</b>: it carries
 * both the clear-text token and the new password, the lesson of {@code AuthenticationCommand}.
 */
public final class ResetPasswordCommand {

    private final String presentedToken;
    private final String newPassword;
    private final SecondFactorProof secondFactor;

    public ResetPasswordCommand(String presentedToken, String newPassword,
            SecondFactorProof secondFactor) {
        this.presentedToken = Objects.requireNonNull(presentedToken, "presentedToken");
        this.newPassword = Objects.requireNonNull(newPassword, "newPassword");
        this.secondFactor = Objects.requireNonNull(secondFactor, "secondFactor");
    }

    public String presentedToken() {
        return presentedToken;
    }

    public String newPassword() {
        return newPassword;
    }

    public SecondFactorProof secondFactor() {
        return secondFactor;
    }

    /** Redacted deliberately: the token and the new password are both secrets. */
    @Override
    public String toString() {
        return "ResetPasswordCommand[presentedToken=REDACTED, newPassword=REDACTED, secondFactor="
                + secondFactor + "]";
    }
}
