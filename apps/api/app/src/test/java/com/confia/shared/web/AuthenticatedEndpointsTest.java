package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.web.authentication.AuthenticatedEndpoint;
import com.confia.shared.web.authentication.AuthenticatedEndpoints;
import com.confia.shared.web.authentication.SessionCheck;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * Specs/web-edge, "La lista de rutas autenticadas es cerrada" (session-tokens-and-web-layer
 * design.md, decision 5): the list is defined in one place, parallel to {@code PublicEndpoints}, is
 * empty in production until the session endpoint arrives, and cannot be changed after it is built.
 */
class AuthenticatedEndpointsTest {

    private static final AuthenticatedEndpoint WHOAMI = new AuthenticatedEndpoint(HttpMethod.GET,
            "/test/whoami", SessionCheck.TOKEN_ONLY);
    private static final AuthenticatedEndpoint LIVE = new AuthenticatedEndpoint(HttpMethod.POST,
            "/test/live", SessionCheck.LIVE_SESSION);

    @Test
    void theProductionListIsEmptyUntilTheSessionEndpointArrives() {
        assertThat(AuthenticatedEndpoints.forAdmin().endpoints()).isEmpty();
    }

    @Test
    void aListHoldsExactlyTheRoutesItWasGiven() {
        assertThat(new AuthenticatedEndpoints(List.of(WHOAMI, LIVE)).endpoints())
                .containsExactly(WHOAMI, LIVE);
    }

    @Test
    void theListIsImmutableAndDetachedFromTheOneItWasBuiltFrom() {
        List<AuthenticatedEndpoint> source = new ArrayList<>(List.of(WHOAMI));
        AuthenticatedEndpoints endpoints = new AuthenticatedEndpoints(source);
        source.add(LIVE);

        assertThat(endpoints.endpoints()).containsExactly(WHOAMI);
        assertThatThrownBy(() -> endpoints.endpoints().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anEntryNamesItsMethodItsPatternAndItsSessionCheck() {
        assertThat(WHOAMI.method()).isEqualTo(HttpMethod.GET);
        assertThat(WHOAMI.pattern()).isEqualTo("/test/whoami");
        assertThat(WHOAMI.check()).isEqualTo(SessionCheck.TOKEN_ONLY);
        assertThat(LIVE.check()).isEqualTo(SessionCheck.LIVE_SESSION);
        assertThatThrownBy(() -> new AuthenticatedEndpoint(null, "/x", SessionCheck.TOKEN_ONLY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuthenticatedEndpoint(HttpMethod.GET, null,
                SessionCheck.TOKEN_ONLY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuthenticatedEndpoint(HttpMethod.GET, "/x", null))
                .isInstanceOf(NullPointerException.class);
    }
}
