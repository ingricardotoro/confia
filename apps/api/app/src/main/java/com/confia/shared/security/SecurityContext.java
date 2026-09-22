package com.confia.shared.security;

import java.util.Objects;

/**
 * The four row-level-security session parameters {@link TransactionRunner} fixes as the first
 * statement of every transaction it opens (docs/03-seguridad.md section 6.2; ADR-0015 rule 7).
 *
 * <p>An absent value (a system actor with no {@code actorId}, or no {@code traceId}) is the empty
 * string, never {@code null}: {@code null} bound to {@code set_config} leaves the session parameter
 * in a state the row-level-security policies do not distinguish from an empty string anyway
 * (design.md, decision 2, "Valores ausentes"; V1 migration's {@code NULLIF(current_setting(...),
 * '')} pattern), so there is no reason to support two different representations of "absent" here.
 */
public record SecurityContext(String actorId, String actorKind, String institutionId,
        String requestId) {

    public SecurityContext {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(actorKind, "actorKind");
        Objects.requireNonNull(institutionId, "institutionId");
        Objects.requireNonNull(requestId, "requestId");
    }
}
