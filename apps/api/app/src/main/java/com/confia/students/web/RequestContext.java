package com.confia.students.web;

import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.RequestOrigin;

/**
 * What the controller of the registry takes from the request it is serving: the staff member of the
 * access token and the identifier of the request. Both are bound by filters of the administrative chain
 * before a controller runs, so their absence is a defect of the wiring and never a client error.
 */
final class RequestContext {

    private RequestContext() {
    }

    static AuthenticatedActor actor() {
        return AuthenticatedActor.current().orElseThrow(
                () -> new IllegalStateException("no authenticated actor is bound to this request"));
    }

    static String requestId() {
        return RequestOrigin.current().orElseThrow(
                () -> new IllegalStateException("no request origin is bound to this request"))
                .requestId().toString();
    }
}
