package com.confia.organization.application;

import com.confia.kernel.InstitutionId;
import com.confia.organization.domain.Institution;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory test double for {@link InstitutionRepository} (design.md, decision 8, "Dobles en
 * memoria" — {@code DO_NOT_INCLUDE_TESTS} keeps it out of production architecture rules).
 * Registers institutions explicitly and returns an empty {@link Optional} for any unregistered
 * identifier, matching the port's contract of never throwing for a plain absence of a result
 * (specs/organization/spec.md, requirement "Puerto de salida para cargar una institución por
 * identificador").
 */
final class InMemoryInstitutionRepository implements InstitutionRepository {

    private final Map<InstitutionId, Institution> institutions = new HashMap<>();

    void register(Institution institution) {
        institutions.put(institution.id(), institution);
    }

    @Override
    public Optional<Institution> findById(InstitutionId id) {
        return Optional.ofNullable(institutions.get(id));
    }
}
