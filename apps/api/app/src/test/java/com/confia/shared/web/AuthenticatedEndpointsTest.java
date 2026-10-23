package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.web.authentication.AuthenticatedEndpoint;
import com.confia.shared.web.authentication.AuthenticatedEndpoints;
import com.confia.shared.security.SecondFactorPurpose;
import com.confia.shared.web.authentication.SecondFactorEndpoint;
import com.confia.shared.web.authentication.SessionCheck;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * Specs/web-edge, "La lista de rutas autenticadas es cerrada" (session-tokens-and-web-layer
 * design.md, decision 5): the list is defined in one place, parallel to {@code PublicEndpoints}, holds
 * the one route of the current session in production, and cannot be changed after it is built.
 */
class AuthenticatedEndpointsTest {

    private static final AuthenticatedEndpoint WHOAMI = new AuthenticatedEndpoint(HttpMethod.GET,
            "/test/whoami", SessionCheck.TOKEN_ONLY);
    private static final AuthenticatedEndpoint LIVE = new AuthenticatedEndpoint(HttpMethod.POST,
            "/test/live", SessionCheck.LIVE_SESSION);

    @Test
    void theProductionListHoldsExactlyTheCurrentSessionRouteWithTheLiveSessionCheck() {
        assertThat(AuthenticatedEndpoints.forAdmin().endpoints()).containsExactly(
                new AuthenticatedEndpoint(HttpMethod.GET, "/api/v1/auth/sessions/current",
                        SessionCheck.LIVE_SESSION));
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

    private static final SecondFactorEndpoint VERIFY = new SecondFactorEndpoint(HttpMethod.POST,
            "/test/second-factor/verify",
            Set.of(SecondFactorPurpose.VERIFY, SecondFactorPurpose.ENROLL));
    private static final SecondFactorEndpoint ENROLL = new SecondFactorEndpoint(HttpMethod.POST,
            "/test/second-factor/enroll", Set.of(SecondFactorPurpose.ENROLL));

    @Test
    void theProductionRestrictedListIsEmptyUntilTheEndpointsExist() {
        assertThat(AuthenticatedEndpoints.forAdmin().secondFactorEndpoints()).isEmpty();
        assertThat(new AuthenticatedEndpoints(List.of(WHOAMI)).secondFactorEndpoints()).isEmpty();
    }

    @Test
    void theRestrictedListHoldsExactlyTheRoutesItWasGivenAndCannotBeChanged() {
        List<SecondFactorEndpoint> source = new ArrayList<>(List.of(VERIFY));
        AuthenticatedEndpoints endpoints = new AuthenticatedEndpoints(List.of(WHOAMI), source);
        source.add(ENROLL);

        assertThat(endpoints.secondFactorEndpoints()).containsExactly(VERIFY);
        assertThat(endpoints.endpoints()).containsExactly(WHOAMI);
        assertThatThrownBy(() -> endpoints.secondFactorEndpoints().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRestrictedEntryNamesItsMethodItsPatternAndAtLeastOnePurpose() {
        assertThat(ENROLL.method()).isEqualTo(HttpMethod.POST);
        assertThat(ENROLL.pattern()).isEqualTo("/test/second-factor/enroll");
        assertThat(ENROLL.purposes()).containsExactly(SecondFactorPurpose.ENROLL);
        assertThatThrownBy(() -> new SecondFactorEndpoint(null, "/x", Set.of(SecondFactorPurpose.VERIFY)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SecondFactorEndpoint(HttpMethod.POST, null,
                Set.of(SecondFactorPurpose.VERIFY))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SecondFactorEndpoint(HttpMethod.POST, "/x", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SecondFactorEndpoint(HttpMethod.POST, "/x", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one purpose");
    }

    @Test
    void theAcceptedPurposesCannotBeChangedAfterwards() {
        Set<SecondFactorPurpose> source = EnumSet.of(SecondFactorPurpose.VERIFY);
        SecondFactorEndpoint entry = new SecondFactorEndpoint(HttpMethod.POST, "/x", source);
        source.add(SecondFactorPurpose.ENROLL);

        assertThat(entry.purposes()).containsExactly(SecondFactorPurpose.VERIFY);
        assertThatThrownBy(() -> entry.purposes().add(SecondFactorPurpose.ENROLL))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
