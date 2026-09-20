package com.confia.organization.application;

import com.confia.kernel.InstitutionId;
import com.confia.organization.domain.Institution;
import com.confia.organization.domain.InstitutionInactiveException;
import com.confia.organization.domain.InstitutionNotFoundException;
import java.util.Objects;

/**
 * Use case that resolves the {@link Institution} the current request belongs to (design.md,
 * decision 8; specs/organization/spec.md, requirement "Caso de uso de resolución de la
 * institución en curso"). Not a Spring bean: no annotation registers it, and it is never scanned
 * from {@code com.confia.bootstrap} — this change adds no adapter for either port yet (design.md,
 * "Alternativas descartadas").
 */
public final class ResolveCurrentInstitution {

    private final CurrentInstitutionProvider currentInstitutionProvider;
    private final InstitutionRepository institutionRepository;

    public ResolveCurrentInstitution(CurrentInstitutionProvider currentInstitutionProvider,
            InstitutionRepository institutionRepository) {
        this.currentInstitutionProvider =
                Objects.requireNonNull(currentInstitutionProvider, "currentInstitutionProvider");
        this.institutionRepository =
                Objects.requireNonNull(institutionRepository, "institutionRepository");
    }

    /**
     * Resolves the active institution for the request currently in flight.
     *
     * @return the current request's active institution
     * @throws InstitutionNotFoundException if no institution is registered for the resolved
     *     identifier
     * @throws InstitutionInactiveException if the resolved institution exists but is not active
     */
    public Institution execute() {
        InstitutionId id = currentInstitutionProvider.currentInstitutionId();
        Institution institution = institutionRepository.findById(id)
                .orElseThrow(InstitutionNotFoundException::new);
        if (!institution.isActive()) {
            throw new InstitutionInactiveException();
        }
        return institution;
    }
}
