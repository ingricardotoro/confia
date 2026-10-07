package com.confia.shared.security.token;

/**
 * A token the codec refused. The message is the name of the {@link TokenRejection} and nothing else,
 * there is never a cause and no stack trace is captured: an attacker sending garbage at line rate
 * costs one small object per request, and neither the token nor any piece of it can reach a log
 * through this exception (CLAUDE.md, regla 11).
 */
public final class TokenRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final TokenRejection rejection;

    public TokenRejectedException(TokenRejection rejection) {
        super(rejection.name(), null, false, false);
        this.rejection = rejection;
    }

    public TokenRejection rejection() {
        return rejection;
    }
}
