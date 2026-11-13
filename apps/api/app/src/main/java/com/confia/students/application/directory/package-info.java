/**
 * The read-only view of the students that {@code students} publishes to the other modules: {@link
 * com.confia.students.application.directory.StudentDirectory} and the {@link
 * com.confia.students.application.directory.StudentRef} it answers. Nothing else of the registry is public.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface names the
 * real consumer that justifies it). The consumer is {@code guardians}, whose use case that replaces the links of a
 * student reads the student, takes the lock on its row that serializes the replacements (INV-32) and shows the code
 * and the names of the linked students, without importing the domain of {@code students} nor reading its tables
 * (docs/cortes/guardians-registry.md) and, through the reference of the source system, resolves the {@code studentLegacyId} of the links import (docs/cortes/imports-guardians.md). The interface is this nested package and not {@code students.application}
 * itself, so the use cases and the repositories of the registry stay internal to the module.
 */
@org.springframework.modulith.NamedInterface
package com.confia.students.application.directory;
