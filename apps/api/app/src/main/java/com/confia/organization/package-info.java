/**
 * The {@code organization} capability: the {@code Institution} aggregate root, the tenant every
 * other business module's data is scoped to (ADR-0009), and the ports and use case that resolve
 * the institution of the current request.
 *
 * <p>Out of scope in this change ({@code institution-root-and-multitenancy-baseline}, F0 change
 * 4): the {@code organization_institution} table, its Flyway migration and its jOOQ repository
 * (change 5, proposal decision D1); the adapter that resolves the current institution from the
 * authenticated token (change 7, ADR-0009); institution administration — create, toggle, screens,
 * endpoints (ADR-0009, point 5, deferred until a second real institution exists).
 *
 * <p>This module has no {@code infrastructure} or {@code web} package yet (proposal, "Dentro de
 * alcance", point 4): the only honest {@code infrastructure} adapter is the jOOQ repository of
 * change 5, and {@code web} has no business use case to expose until administration lands. Their
 * absence is intentional, not an oversight — see ADR-0018 (opción C) and ADR-0020, which keeps
 * {@code LayeredArchitectureTest}'s production layering rule passing with those two layers
 * declared {@code optionalLayer(...)} until each gets its first class.
 */
package com.confia.organization;
