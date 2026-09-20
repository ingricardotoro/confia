/**
 * Compilation boundary shared by every CONFIA business module.
 *
 * <p>This package, and every package under {@code com.confia.kernel}, depends on nothing outside
 * the JDK (ADR-0002, ADR-0013). The {@code maven-enforcer-plugin} execution in this module's
 * {@code pom.xml} breaks the build if a compile/runtime/provided dependency is ever added here.
 *
 * <p>No business rule lives in this package: it exists only to hold cross-cutting, framework-free
 * building blocks ({@code Money}, {@code CurrencyCode}, {@code Percentage}, {@code
 * DomainException} and identifiers such as {@code InstitutionId}).
 *
 * <p><strong>Every public type of this module lives directly in this single, flat package —
 * there are no subpackages such as {@code com.confia.kernel.money}.</strong> {@code app} depends
 * on {@code confia-kernel}, and {@link com.confia.architecture.SpringModulithVerificationTest}
 * builds {@code ApplicationModules.of("com.confia", ...)}, which treats every direct subpackage of
 * {@code com.confia} as its own Spring Modulith module and, by default, exposes only that
 * subpackage's own base package as API. A {@code com.confia.kernel.money} subpackage would make
 * the first business module that imports {@code Money} (change 4) violate that verification;
 * exposing it would need {@code @NamedInterface} or {@code @ApplicationModule}, both Spring
 * Modulith annotations that would violate {@code enforce-kernel-purity} above (design.md, decision
 * 1). If the module ever grows enough to need real subpackages, that split is a deliberate
 * decision made with its own ADR, not an incidental one.
 */
package com.confia.kernel;
