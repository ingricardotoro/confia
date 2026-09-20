package com.confia.organization.domain;

import com.confia.kernel.DomainException;

/**
 * Thrown by {@code ResolveCurrentInstitution} (design.md, decision 8) when the resolved {@code
 * Institution} exists but has {@code isActive} false (specs/organization/spec.md, requirement
 * "Caso de uso de resolución de la institución en curso", scenario "Rechazo de una institución
 * inactiva"). Deliberately distinct from {@link InstitutionStateException}'s
 * {@code institution-already-inactive}: this one means "cannot operate with this institution",
 * that one means "invalid transition" (design.md, decision 6).
 *
 * <p>Unlike {@link InvalidInstitutionException} and {@link InstitutionStateException}, this
 * exception is raised outside the {@code organization.domain} package (by the {@code application}
 * layer's use case), so its constructor is public rather than a package-private factory
 * (design.md, decision 6).
 */
public final class InstitutionInactiveException extends DomainException {

    public static final String CODE = "institution-inactive";

    public InstitutionInactiveException() {
        super(CODE, "institution is not active");
    }
}
