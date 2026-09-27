package com.confia.identity.application;

import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PlainPassword;
import java.util.Objects;

/**
 * The input {@link AuthenticateWithPassword} receives: the identifier and password exactly as
 * presented, not yet normalized. Normalization happens once, at the boundary {@link
 * LoginIdentifier#of} and {@link PlainPassword#of} themselves apply (design.md, decision 11) —
 * this record carries the raw, pre-normalization strings on purpose, so there is exactly one place
 * downstream that normalizes either value.
 */
public record AuthenticationCommand(String presentedIdentifier, String presentedPassword) {

    public AuthenticationCommand {
        Objects.requireNonNull(presentedIdentifier, "presentedIdentifier");
        Objects.requireNonNull(presentedPassword, "presentedPassword");
    }
}
