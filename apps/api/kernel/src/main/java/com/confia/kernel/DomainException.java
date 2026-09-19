package com.confia.kernel;

import java.util.regex.Pattern;

/**
 * Base class for every domain error raised anywhere in the system (ADR-0019). Unchecked on
 * purpose: a port's signature never gets polluted by a {@code throws} clause because a business
 * rule was violated. {@code code()} exposes a stable, machine-readable identifier that the {@code
 * web} layer uses, unchanged, as the last segment of a Problem Details {@code type} (RFC 9457).
 *
 * <p>An argument that is not a business condition — a null reference, an invalid allocation
 * weight, a rounding mode that demands impossible exactness — is a programming error and is
 * signalled with a JDK exception instead ({@link NullPointerException}, {@link
 * IllegalArgumentException}, {@link ArithmeticException}), never with a {@code DomainException}
 * subclass.
 */
public abstract class DomainException extends RuntimeException {

    private static final Pattern CODE_FORMAT = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    private static final int MAX_CODE_LENGTH = 64;

    private final String code;

    /**
     * @param code stable, machine-readable identifier in kebab-case, {@code <subject>-<condition>}
     *     (for example {@code currency-mismatch}), at most 64 characters. Rejecting a malformed
     *     code here is itself a programming error, not a domain one: it throws {@link
     *     IllegalArgumentException}, never a {@code DomainException}.
     * @param message technical message in English, for logs and debugging only; never shown to the
     *     end user and never repeats raw user input (CLAUDE.md, rule 11).
     */
    protected DomainException(String code, String message) {
        super(message);
        this.code = requireValidCode(code);
    }

    public final String code() {
        return code;
    }

    private static String requireValidCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("code must not be null");
        }
        if (code.length() > MAX_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "code must be at most " + MAX_CODE_LENGTH + " characters long, was "
                            + code.length());
        }
        if (!CODE_FORMAT.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "code must be lowercase kebab-case matching " + CODE_FORMAT.pattern()
                            + ", was: " + code);
        }
        return code;
    }
}
