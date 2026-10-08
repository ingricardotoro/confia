package com.confia.shared.web.authentication;

/**
 * A bearer token whose signature and claims are intact and whose time has passed. It is its own type
 * so that the entry point answers {@code token-expired}, which tells a client it can renew, instead
 * of {@code token-invalid}. Only the verifier's {@code EXPIRED} reason produces it, and the verifier
 * reports that reason only after the signature and every claim have been checked.
 */
public final class AccessTokenExpiredException extends AccessTokenRejectedException {

    private static final long serialVersionUID = 1L;
}
