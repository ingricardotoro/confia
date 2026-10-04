/**
 * OpenAPI contract pieces shared by every process (frontend-monorepo-and-contracts-pipeline,
 * design.md decision 4): today, only the two cross-cutting schemas the contract already fixes.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface of
 * {@code shared} names the real consumer that justifies it). The consumers are the two web entry
 * points, {@code bootstrap.admin.AdminApplication} and {@code bootstrap.portal.PortalApplication},
 * which {@code @Import} {@link com.confia.shared.web.openapi.ContractSchemas} and {@link
 * com.confia.shared.web.openapi.ProcessApiInfo} explicitly, with no component scan (ADR-0024).
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.web.openapi;
