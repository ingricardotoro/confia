/**
 * The write side of the bulk import of students that {@code students} publishes to the other modules:
 * {@link com.confia.students.application.importing.StudentImportGateway} and the small records it exchanges. Nothing
 * else of the registry is public.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface names the real
 * consumer that justifies it). The consumer is {@code imports}, whose use case reads a CSV file, asks this port to
 * judge every row with the rules of the registry and to create the valid ones through the same use case as the hand
 * registration (code, encrypted document with its blind index, audit), without importing the domain of {@code
 * students} nor reading its tables (docs/cortes/imports-students.md). The interface is this nested package and not
 * {@code students.application} itself, so the use cases and the repositories of the registry stay internal.
 */
@org.springframework.modulith.NamedInterface
package com.confia.students.application.importing;
