/**
 * Adapter layer of the {@code organization} module (ADR-0015 rule 4: jOOQ lives only in {@code
 * infrastructure}). {@link com.confia.organization.infrastructure.JooqInstitutionRepository} is
 * this module's first adapter, implementing {@link
 * com.confia.organization.application.InstitutionRepository} against the real {@code
 * organization_institution} table (design.md decision 8; F0 change 5, PR A2).
 */
package com.confia.organization.infrastructure;
