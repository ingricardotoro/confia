/**
 * The shared audit-trail module: canonical serialization of a {@code shared_audit_log} row (PR
 * B3a) and the chain verifier that recalculates it from an institution's genesis record (PR B3b),
 * plus the read-only port ({@link com.confia.shared.audit.AuditLogReader}) the verifier depends on
 * (design.md decision 11; ADR-0015 rule 3, "el módulo {@code shared} es dueño del prefijo {@code
 * Shared}").
 *
 * <p>This package carries no layer segment ({@code domain}, {@code application}, {@code
 * infrastructure} or {@code web}), exactly like {@link com.confia.shared.security}: {@link
 * com.confia.architecture.LayeredArchitectureTest} treats a package with no layer segment as
 * outside the layered architecture entirely, which is what lets {@link
 * com.confia.shared.infrastructure.JooqAuditLogReader} — a real {@code infrastructure} class —
 * depend on this package's ports without a layering violation, while jOOQ itself stays confined to
 * {@code infrastructure} (ADR-0015 rule 4, R1; design.md decision 11, "Dónde vive, contra las
 * reglas de dependencia").
 *
 * <p><b>{@code @NamedInterface} (ADR-0022).</b> Like {@link com.confia.shared.security},
 * {@code identity-module-and-password-authentication} (PR C3) needs {@code identity.application}
 * to consume {@link com.confia.shared.audit.AuditLogWriter} across the module boundary Spring
 * Modulith enforces (design.md, decision 12). Declared here, in this same PR, alongside {@code
 * shared.security}'s own annotation, so both of the two packages {@code identity} needs are named
 * API from the same commit that elevates the decision to ADR-0022.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.audit;
