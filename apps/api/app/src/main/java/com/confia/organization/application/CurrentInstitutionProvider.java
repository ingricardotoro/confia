package com.confia.organization.application;

import com.confia.kernel.InstitutionId;

/**
 * Output port that resolves the {@link InstitutionId} of the request currently in flight
 * (specs/organization/spec.md, requirement "Puerto de salida para la institución de la solicitud
 * en curso").
 *
 * <p><b>ADR-0009 contract.</b> The returned identifier MUST be derived from the authenticated
 * token, and MUST NEVER come from a client-supplied parameter, header or request body
 * ("Implementación del aislamiento": "el identificador de institución de la sesión se establece
 * ... a partir del token autenticado y nunca a partir de un parámetro del cliente"). The real
 * adapter over Spring Security is change 7's responsibility; this specification does not require
 * that every invocation resolve an identifier — how an adaptor behaves without an authenticated
 * session is that adaptor's own decision.
 *
 * <p>Advanced from its nominal task 4.1 to this pull request (owner decision, 2026-09-19): ADR-0020
 * §2 keeps the {@code Application} layer always mandatory in {@code
 * com.confia.architecture.LayeredArchitectureTest}, so it needs a real production class before task
 * 2.4 applies {@code optionalLayer} to {@code Infrastructure} and {@code Web}. No behavior is added
 * here yet; the use case that consumes this port lands in task 4.1.
 */
public interface CurrentInstitutionProvider {

    /**
     * Resolves the identifier of the institution the current request belongs to.
     *
     * @return the current request's institution identifier, derived from the authenticated token
     *     only — never from a parameter, header or body the client controls
     */
    InstitutionId currentInstitutionId();
}
