/**
 * Compilation boundary shared by every CONFIA business module.
 *
 * <p>This package, and every package under {@code com.confia.kernel}, depends on nothing outside
 * the JDK (ADR-0002, ADR-0013). The {@code maven-enforcer-plugin} execution in this module's
 * {@code pom.xml} breaks the build if a compile/runtime/provided dependency is ever added here.
 *
 * <p>No business rule lives in this package: it exists only to hold cross-cutting, framework-free
 * building blocks ({@code Money}, identifiers, the base domain error) once a change needs them.
 */
package com.confia.kernel;
