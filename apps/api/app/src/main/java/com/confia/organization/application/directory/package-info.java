/**
 * The read-only view of the academic structure that {@code organization} publishes to the other modules: {@link
 * com.confia.organization.application.directory.SectionDirectory} and the {@link
 * com.confia.organization.application.directory.SectionRef} it answers, and {@link
 * com.confia.organization.application.directory.AcademicStructureDirectory}, which only says whether a year, a modality
 * a grade or a section exists in an institution. Nothing else of the academic structure is public.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface names the
 * real consumer that justifies it). The consumer is {@code students}, whose enrollment use case reads the
 * section, its academic year, grade and modality, takes the lock on the row of the section that makes the
 * capacity check safe and resolves the active year, without importing the domain of {@code organization}
 * nor reading its tables (docs/cortes/enrollments.md). The second consumer is {@code catalog}, whose price lists belong
 * to a year and may belong to a modality or a grade, and which must prove that they are of the institution of the
 * session through {@code AcademicStructureDirectory} (docs/cortes/catalog-price-lists.md). The third consumer is {@code
 * charges}, whose plans belong to a year and target a modality, a grade or a section of the institution of the session,
 * proven through the same directory (docs/cortes/charges-plans.md). The interface is this nested package and not {@code
 * organization.application} itself, so the use cases and the repositories of the structure stay internal to
 * the module.
 */
@org.springframework.modulith.NamedInterface
package com.confia.organization.application.directory;
