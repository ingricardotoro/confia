package com.confia.shared.web.authentication;

import org.springframework.security.core.AuthenticationException;

/**
 * A bearer credential that was presented and cannot be accepted: a malformed header, a token the
 * verifier refuses, or a session that is over. The entry point answers it with {@code token-invalid}.
 *
 * <p>It carries a fixed message, never the token or a piece of it, and no stack trace: a client that
 * sends garbage at line rate costs one small object per request, and nothing the client sent can
 * reach a log through this exception (CLAUDE.md, regla 11).
 */
public class AccessTokenRejectedException extends AuthenticationException {

    private static final long serialVersionUID = 1L;

    public AccessTokenRejectedException() {
        super("the bearer credential was rejected");
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
