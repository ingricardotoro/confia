package com.confia.shared.web.problem;

import java.net.URI;
import java.util.Optional;

/**
 * The stable error codes of the web edge and the HTTP status each one carries (web-edge-foundations
 * design.md, decision 8; ADR-0019). A code is published to clients and never renamed, and a code
 * cannot repeat because this is an enum.
 *
 * <p>This change owns only the codes its own producers can emit: the security chain's uniform
 * denials ({@link #AUTHENTICATION_REQUIRED} and {@link #FORBIDDEN}), the firewall's rejection and
 * the translator's validation failures ({@link #VALIDATION_FAILED}), the translator's
 * {@link #RESOURCE_NOT_FOUND} (a file that does not exist under a documentation prefix) and
 * {@link #UNSUPPORTED_MEDIA_TYPE}, and the last resort ({@link #INTERNAL_ERROR}). {@link
 * #METHOD_NOT_ALLOWED} is the one answer to {@code TRACE}, which the container refuses before any
 * filter runs (task 2.2; design.md, decision 8, dated note). The sign-in codes
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
    RESOURCE_NOT_FOUND("resource-not-found", 404),
    METHOD_NOT_ALLOWED("method-not-allowed", 405),
    UNSUPPORTED_MEDIA_TYPE("unsupported-media-type", 415),
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

    /**
     * The code that answers an HTTP status someone else chose, the one rule of the container's
     * error report and of the MVC translator: {@code 401}, {@code 403}, {@code 404}, {@code 405}
     * and {@code 415} have their own codes, any other client error is {@link #VALIDATION_FAILED},
     * and a server error is {@link #INTERNAL_ERROR}. The status of the answer is the one of the
     * code, not the one given, so a body and its response always agree.
     */
    public static ProblemCode forStatus(int status) {
        return switch (status) {
            case 401 -> AUTHENTICATION_REQUIRED;
            case 403 -> FORBIDDEN;
            case 404 -> RESOURCE_NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status >= 500 ? INTERNAL_ERROR : VALIDATION_FAILED;
        };
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
