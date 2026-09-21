package com.confia.organization.application;

import com.confia.kernel.InstitutionId;
import com.confia.organization.domain.Institution;
import java.util.Optional;

/**
 * Output port that loads an {@link Institution} by its {@link InstitutionId}
 * (specs/organization/spec.md, requirement "Puerto de salida para cargar una institución por
 * identificador"). This interface never depends on jOOQ, on a PostgreSQL type, or on any other
 * infrastructure detail. Its real implementation is {@link
 * com.confia.organization.infrastructure.JooqInstitutionRepository}, against the {@code
 * organization_institution} table (F0 change 5, PR A2; design.md decision 8).
 *
 * <p>Advanced from its nominal task 4.1 to this pull request (owner decision, 2026-09-19): ADR-0020
 * §2 keeps the {@code Application} layer always mandatory in {@code
 * com.confia.architecture.LayeredArchitectureTest}, so it needs a real production class before task
 * 2.4 applies {@code optionalLayer} to {@code Infrastructure} and {@code Web}. No behavior is added
 * here yet; the use case that consumes this port lands in task 4.1.
 */
public interface InstitutionRepository {

    /**
     * Loads the institution identified by {@code id}.
     *
     * @param id the institution's identifier; never {@code null}
     * @return the matching institution, or an empty {@link Optional} when no institution is
     *     registered under {@code id} — never an exception for a plain absence of a result
     */
    Optional<Institution> findById(InstitutionId id);
}
