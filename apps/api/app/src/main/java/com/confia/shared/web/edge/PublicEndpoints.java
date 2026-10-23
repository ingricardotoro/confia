package com.confia.shared.web.edge;

import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;

/**
 * The closed, immutable allow-list of routes the administrative and the portal chains answer
 * without a credential, defined in this one place (web-edge-foundations design.md, decision 5).
 * Everything not listed is denied, whether or not a controller serves it.
 *
 * <p>The administrative list holds the login, the refresh and the end of the current session, always
 * (session-endpoints-cookie-and-csrf design.md
 * decision 11), and the API documentation, only when springdoc is switched on, which the {@code
 * local} and {@code preprod} profiles do and nothing else does; the portal list holds only the
 * documentation. The condition is the
 * very property that enables springdoc, not the name of a profile: one source decides both whether
 * the documentation exists and whether it is public, and a new or misspelled profile leaves it
 * closed. There are no health or readiness routes because there is no actuator; the change that
 * adds one edits this class, and the allow-list test of change 7 part 4a then obliges it.
 *
 * <p>The public constructor exists for the test harnesses, which add routes of their own to the
 * real list and nothing else.
 */
public final class PublicEndpoints {

    static final String API_DOCS_ENABLED = "springdoc.api-docs.enabled";
    static final String SWAGGER_UI_ENABLED = "springdoc.swagger-ui.enabled";

    /**
     * The login, the one public route of production in the administrative process
     * (session-endpoints-cookie-and-csrf design.md decision 11). It carries no credential by
     * definition, so it is public to the chain; what protects it is the limiter its handler declares
     * (condition H1), the allowed origin the CSRF filter demands and the JSON content type.
     */
    static final PublicEndpoint LOGIN = new PublicEndpoint(HttpMethod.POST, "/api/v1/auth/sessions");

    /**
     * The refresh (session-endpoints-cookie-and-csrf design.md decision 14). Its credential is the
     * session cookie, so the chain lets it through and what protects it is the CSRF filter (the
     * double-submit token with the cookie, an allowed Origin always) and the limiter its handler
     * declares.
     */
    static final PublicEndpoint REFRESH =
            new PublicEndpoint(HttpMethod.POST, "/api/v1/auth/sessions/refresh");

    /**
     * The end of the current session (decision 15). It takes no access token, so a client whose access
     * expired can still end its session; what protects it is the CSRF filter (the double-submit token
     * with the session cookie, an allowed Origin always) and the limiter its handler declares.
     */
    static final PublicEndpoint END_CURRENT_SESSION =
            new PublicEndpoint(HttpMethod.DELETE, "/api/v1/auth/sessions/current");

    private final List<PublicEndpoint> endpoints;

    public PublicEndpoints(List<PublicEndpoint> endpoints) {
        this.endpoints = List.copyOf(endpoints);
    }

    /** The allow-list of the administrative process. */
    public static PublicEndpoints forAdmin(Environment environment) {
        List<PublicEndpoint> entries = new ArrayList<>();
        entries.add(LOGIN);
        entries.add(REFRESH);
        entries.add(END_CURRENT_SESSION);
        entries.addAll(documentation(environment).endpoints());
        return new PublicEndpoints(entries);
    }

    /**
     * The allow-list of the portal process: the same documentation entries and nothing of its
     * own, so with the default (production) configuration it is empty and the portal denies
     * everything.
     */
    public static PublicEndpoints forPortal(Environment environment) {
        return documentation(environment);
    }

    /** The routes, in a list that cannot be modified. */
    public List<PublicEndpoint> endpoints() {
        return endpoints;
    }

    private static PublicEndpoints documentation(Environment environment) {
        List<PublicEndpoint> entries = new ArrayList<>();
        if (environment.getProperty(API_DOCS_ENABLED, Boolean.class, false)) {
            entries.add(new PublicEndpoint(HttpMethod.GET, "/v3/api-docs"));
            entries.add(new PublicEndpoint(HttpMethod.GET, "/v3/api-docs/swagger-config"));
        }
        if (environment.getProperty(SWAGGER_UI_ENABLED, Boolean.class, false)) {
            entries.add(new PublicEndpoint(HttpMethod.GET, "/swagger-ui.html"));
            entries.add(new PublicEndpoint(HttpMethod.GET, "/swagger-ui/**"));
        }
        return new PublicEndpoints(entries);
    }
}
