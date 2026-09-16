/**
 * Architecture verification for {@code apps/api/app}: ArchUnit rules (ADR-0002) and the Spring
 * Modulith 2 module-boundary check (ADR-0013), each proven against the permanent fixture package
 * {@link com.confia.architecture.fixture} (design.md decision 3).
 *
 * <p><strong>No rule in this package is ever disabled, suppressed or excluded without an explicit
 * ADR reference cited next to the exclusion</strong> ({@code build-integrity} requirement "Ninguna
 * regla se desactiva sin un ADR"). As of this change, no exception exists: every rule here applies
 * with zero exclusions. If a future change needs one, it adds the exclusion here with a comment
 * naming the authorizing ADR, never elsewhere and never silently.
 */
package com.confia.architecture;
