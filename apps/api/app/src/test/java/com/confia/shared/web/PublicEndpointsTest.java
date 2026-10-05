package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.edge.PublicEndpoint;
import com.confia.shared.web.edge.PublicEndpoints;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;

/**
 * Specs/web-edge, "La lista blanca pública es cerrada" (design.md decision 5): the allow-list is
 * decided by the very properties that switch springdoc on, so one source says both whether the
 * documentation exists and whether it is public, and a profile nobody listed leaves it closed.
 */
class PublicEndpointsTest {

    private static final PublicEndpoint API_DOCS = new PublicEndpoint(HttpMethod.GET,
            "/v3/api-docs");
    private static final PublicEndpoint SWAGGER_CONFIG = new PublicEndpoint(HttpMethod.GET,
            "/v3/api-docs/swagger-config");
    private static final PublicEndpoint SWAGGER_PAGE = new PublicEndpoint(HttpMethod.GET,
            "/swagger-ui.html");
    private static final PublicEndpoint SWAGGER_ASSETS = new PublicEndpoint(HttpMethod.GET,
            "/swagger-ui/**");

    private static MockEnvironment springdoc(boolean apiDocs, boolean swaggerUi) {
        return new MockEnvironment()
                .withProperty("springdoc.api-docs.enabled", Boolean.toString(apiDocs))
                .withProperty("springdoc.swagger-ui.enabled", Boolean.toString(swaggerUi));
    }

    @Test
    void withNothingConfiguredTheAllowListIsEmptyForBothProcesses() {
        assertThat(PublicEndpoints.forAdmin(new MockEnvironment()).endpoints()).isEmpty();
        assertThat(PublicEndpoints.forPortal(new MockEnvironment()).endpoints()).isEmpty();
    }

    @Test
    void withSpringdocOffTheAllowListIsEmpty() {
        assertThat(PublicEndpoints.forAdmin(springdoc(false, false)).endpoints()).isEmpty();
    }

    @Test
    void withBothSpringdocSwitchesOnTheAllowListHoldsExactlyTheFourDocumentationEntries() {
        assertThat(PublicEndpoints.forAdmin(springdoc(true, true)).endpoints())
                .containsExactlyInAnyOrder(API_DOCS, SWAGGER_CONFIG, SWAGGER_PAGE,
                        SWAGGER_ASSETS);
    }

    @Test
    void eachSwitchOpensOnlyItsOwnEntries() {
        assertThat(PublicEndpoints.forAdmin(springdoc(true, false)).endpoints())
                .containsExactlyInAnyOrder(API_DOCS, SWAGGER_CONFIG);
        assertThat(PublicEndpoints.forAdmin(springdoc(false, true)).endpoints())
                .containsExactlyInAnyOrder(SWAGGER_PAGE, SWAGGER_ASSETS);
    }

    @Test
    void thePortalAllowListIsTheSameDocumentationEntriesAndNothingOfItsOwn() {
        MockEnvironment local = springdoc(true, true);
        assertThat(PublicEndpoints.forPortal(local).endpoints())
                .containsExactlyInAnyOrderElementsOf(PublicEndpoints.forAdmin(local).endpoints());
        assertThat(PublicEndpoints.forPortal(springdoc(false, false)).endpoints()).isEmpty();
    }

    @Test
    void everyEntryIsGetOnly() {
        assertThat(PublicEndpoints.forAdmin(springdoc(true, true)).endpoints())
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.method()).isEqualTo(HttpMethod.GET));
    }

    @Test
    void theAllowListIsImmutable() {
        PublicEndpoints endpoints = PublicEndpoints.forAdmin(springdoc(true, true));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> endpoints.endpoints().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
