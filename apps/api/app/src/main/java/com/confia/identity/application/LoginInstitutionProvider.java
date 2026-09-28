package com.confia.identity.application;

import com.confia.kernel.InstitutionId;

/**
 * The institution authentication resolves against, from process configuration only (design.md,
 * §6.1, decision 11; specs/identity/spec.md, requirement "La institución previa a la autenticación
 * proviene de la configuración del proceso"). NEVER the request body, a header nor a query
 * parameter: login happens before any authenticated token exists to derive an institution from,
 * which is exactly why this is its own port and not {@code organization}'s own {@code
 * CurrentInstitutionProvider} — that contract requires deriving the id from the authenticated
 * token, a promise this path cannot keep. {@link
 * com.confia.identity.infrastructure.ConfiguredLoginInstitutionProvider} is its real adapter,
 * arriving in PR C3b.
 */
public interface LoginInstitutionProvider {

    InstitutionId loginInstitutionId();
}
