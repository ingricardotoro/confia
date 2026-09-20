/**
 * Architecture verification for {@code apps/api/app}: ArchUnit rules (ADR-0002) and the Spring
 * Modulith 2 module-boundary check (ADR-0013), each proven against the permanent fixture package
 * {@link com.confia.architecture.fixture} (design.md decision 3).
 *
 * <p><strong>No rule in this package is ever disabled, suppressed or excluded without an explicit
 * ADR reference cited next to the exclusion</strong> ({@code build-integrity} requirement "Ninguna
 * regla se desactiva sin un ADR"). The exceptions today are the two optional adapter layers of the
 * production half of {@link com.confia.architecture.LayeredArchitectureTest}, authorized by
 * ADR-0020 for exactly as long as the {@code organization} module has no class under an
 * {@code infrastructure} or a {@code web} package; ADR-0018's own exception expired and was removed
 * the day the first business module landed. {@link
 * com.confia.architecture.EmptyShouldExceptionInventoryTest} fails the build the day that
 * condition stops holding, and {@link com.confia.architecture.SuppressionCitesAdrTest} fails it if
 * any suppression here, present or future, is added without citing the ADR that authorizes it.
 */
package com.confia.architecture;
