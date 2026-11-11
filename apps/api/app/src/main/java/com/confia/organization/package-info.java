/**
 * The {@code organization} capability: the {@code Institution} aggregate root, the tenant every
 * other business module's data is scoped to (ADR-0009), the ports and use case that resolve the
 * institution of the current request, and the academic structure of an institution: its academic
 * years, modalities, grades and sections (docs/cortes/organization-academic-structure.md), with their
 * four use cases ({@code ManageAcademicYears}, {@code ManageModalities}, {@code ManageGrades} and
 * {@code ManageSections}), their jOOQ adapters and their twelve routes under {@code
 * /api/v1/organization}.
 *
 * <p>Out of scope: institution administration (create, toggle, screens, endpoints; ADR-0009, point
 * 5, deferred until a second real institution exists), the billing periods, students, guardians and
 * enrollment.
 *
 * <p>The adapter that resolves the current institution from the authenticated token ({@link
 * com.confia.organization.infrastructure.TokenCurrentInstitutionProvider}, ADR-0009) arrived with
 * {@code session-tokens-and-web-layer}. Nothing in this module is ever deleted: a row of the academic
 * structure is deactivated with an update, and every write leaves an entry in the audit trail.
 */
package com.confia.organization;
