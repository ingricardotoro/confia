package com.confia.organization.infrastructure;

import com.confia.kernel.InstitutionId;
import com.confia.organization.application.CurrentInstitutionProvider;
import com.confia.shared.security.AuthenticatedActor;

/**
 * The production adapter of {@link CurrentInstitutionProvider} (session-tokens-and-web-layer
 * design.md, decision 6; ADR-0009): the institution of the request is the one of the authenticated
 * actor, taken from the {@code tenant} claim of the verified access token, and from nothing else.
 *
 * <p>It receives no request and reads no header, query parameter, cookie or body, so nothing the
 * client controls can reach it. It does not read the institution of the process configuration
 * either: that one belongs to the operations that run before anyone is authenticated. The actor is
 * bound per request in a scoped value, so two requests served at the same time, even by the same
 * thread in turn, never see each other's institution.
 *
 * <p>Without an authenticated actor it fails closed with an {@link IllegalStateException} and
 * returns no institution: a route that asks for the institution before authenticating is a wiring
 * defect, not a client error, so the edge answers it as an internal error. The message names no
 * institution and nothing of the request.
 */
public final class TokenCurrentInstitutionProvider implements CurrentInstitutionProvider {

    @Override
    public InstitutionId currentInstitutionId() {
        AuthenticatedActor actor = AuthenticatedActor.current().orElseThrow(
                () -> new IllegalStateException("no authenticated actor is bound to this request"));
        return new InstitutionId(actor.institutionId());
    }
}
