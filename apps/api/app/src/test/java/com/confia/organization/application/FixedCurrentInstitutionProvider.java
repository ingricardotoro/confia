package com.confia.organization.application;

import com.confia.kernel.InstitutionId;

/**
 * Test double for {@link CurrentInstitutionProvider} that always resolves the same, configured
 * identifier (design.md, decision 8, "Dobles en memoria"; specs/organization/spec.md, requirement
 * "Puerto de salida para la institución de la solicitud en curso").
 */
final class FixedCurrentInstitutionProvider implements CurrentInstitutionProvider {

    private final InstitutionId institutionId;

    FixedCurrentInstitutionProvider(InstitutionId institutionId) {
        this.institutionId = institutionId;
    }

    @Override
    public InstitutionId currentInstitutionId() {
        return institutionId;
    }
}
