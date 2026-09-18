/**
 * Architecture verification for {@code apps/api/app}: ArchUnit rules (ADR-0002) and the Spring
 * Modulith 2 module-boundary check (ADR-0013), each proven against the permanent fixture package
 * {@link com.confia.architecture.fixture} (design.md decision 3).
 *
 * <p><strong>No rule in this package is ever disabled, suppressed or excluded without an explicit
 * ADR reference cited next to the exclusion</strong> ({@code build-integrity} requirement "Ninguna
 * regla se desactiva sin un ADR"). The only exception today is {@code allowEmptyShould(true)} on
 * the production half of {@link com.confia.architecture.LayeredArchitectureTest}, authorized by
 * ADR-0018 for exactly as long as no business module exists under {@code apps/api/app}. {@link
 * com.confia.architecture.EmptyShouldExceptionInventoryTest} fails the build the day that
 * condition stops holding, and {@link com.confia.architecture.SuppressionCitesAdrTest} fails it if
 * any suppression here, present or future, is added without citing the ADR that authorizes it.
 */
package com.confia.architecture;
