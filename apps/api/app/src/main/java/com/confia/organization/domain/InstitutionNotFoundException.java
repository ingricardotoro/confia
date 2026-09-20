package com.confia.organization.domain;

import com.confia.kernel.DomainException;

/**
 * Thrown by {@code ResolveCurrentInstitution} (design.md, decision 8) when {@code
 * InstitutionRepository} finds no institution for the request's resolved identifier
 * (specs/organization/spec.md, requirement "Caso de uso de resolución de la institución en
 * curso", scenario "Rechazo de una institución inexistente"). The identifier never comes from the
 * client (ADR-0009), so distinguishing it from {@link InstitutionInactiveException} leaks nothing
 * to a caller; the {@code web} layer decides the HTTP translation when it lands.
 *
 * <p>Unlike {@link InvalidInstitutionException} and {@link InstitutionStateException}, this
 * exception is raised outside the {@code organization.domain} package (by the {@code application}
 * layer's use case), so its constructor is public rather than a package-private factory
 * (design.md, decision 6).
 */
public final class InstitutionNotFoundException extends DomainException {

    public static final String CODE = "institution-not-found";

    public InstitutionNotFoundException() {
        super(CODE, "no institution found for the resolved identifier");
    }
}
