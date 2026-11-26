/**
 * The public API of {@code scholarships} for the module that accrues charges: {@link
 * com.confia.scholarships.application.api.AwardBenefits}, the read-only view of what the approved awards in force of a student offer
 * on a charge (docs/cortes/scholarships-awards.md), with the plain types it answers (no type of the scholarships domain crosses the
 * boundary).
 *
 * <p><b>{@code @NamedInterface("api")}, and who consumes it</b> (ADR-0022: every new named interface names the real consumer that
 * justifies it). The consumer is {@code charges}, whose accrual engine applies the benefit of an approved award to the charges it
 * generates, in the preview, in the run of the worker and in the simulator. This package is a nested one so the rest of {@code
 * scholarships.application} stays internal to the module.
 */
@org.springframework.modulith.NamedInterface("api")
package com.confia.scholarships.application.api;
