package com.confia.shared.web.problem;

import java.net.URI;
import java.util.Optional;

/**
 * The stable error codes of the web edge and the HTTP status each one carries (web-edge-foundations
 * design.md, decision 8; ADR-0019). A code is published to clients and never renamed, and a code
 * cannot repeat because this is an enum.
 *
 * <p>This change owns only the codes its own producers can emit: the security chain's uniform
 * denials ({@link #AUTHENTICATION_REQUIRED} and {@link #FORBIDDEN}), the firewall's rejection
 * ({@link #VALIDATION_FAILED}) and the last resort ({@link #INTERNAL_ERROR}). The sign-in codes
 * ({@code authentication-failed}, {@code token-invalid}, {@code token-expired}) and the
 * institution codes belong to the session change and do not exist yet. {@link
 * #AUTHENTICATION_REQUIRED} names the observable condition and never the cause, which is what
 * makes the denial uniform.
 *
 * <p>The {@code type} of a problem is derived from the code and from nothing else: not from a
 * class name, a message or a stack trace.
 */
public enum ProblemCode {

    VALIDATION_FAILED("validation-failed", 400),
    AUTHENTICATION_REQUIRED("authentication-required", 401),
    FORBIDDEN("forbidden", 403),
    INTERNAL_ERROR("internal-error", 500);

    private static final String TYPE_BASE = "https://confia.hn/problems/";
    private static final String KEY_PREFIX = "problem.";

    private final String code;
    private final int status;

    ProblemCode(String code, int status) {
        this.code = code;
        this.status = status;
    }

    /** The stable code, in kebab case. */
    public String code() {
        return code;
    }

    /** The HTTP status of a response that carries this code. */
    public int status() {
        return status;
    }

    /** The problem {@code type}: the stable base followed by the code. */
    public URI type() {
        return URI.create(TYPE_BASE + code);
    }

    /** The catalog key of the {@code title}. */
    public String titleKey() {
        return KEY_PREFIX + code + ".title";
    }

    /** The catalog key of the {@code detail}. */
    public String detailKey() {
        return KEY_PREFIX + code + ".detail";
    }

    /** The code with exactly this text, or empty for an unknown, misspelled or {@code null} one. */
    public static Optional<ProblemCode> ofCode(String code) {
        for (ProblemCode candidate : values()) {
            if (candidate.code.equals(code)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
