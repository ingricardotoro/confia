package com.confia.shared.web.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.web.authorization.RouteCoverageGuard.Classified;
import com.confia.shared.web.authorization.RouteCoverageGuard.Registered;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The guard of the start (rbac-permission-matrix-and-audit-integration design.md D13; scenarios AZ34,
 * AZ35): a route that is on none of the four lists, an entry of the permission list that no route
 * serves, a route on two lists, a functional router and an empty scan each stop the start, and a
 * process whose routes are all classified once starts. The routes are registered in a real handler
 * mapping, so the guard reads what the framework registers and not a copy of it.
 */
class RouteCoverageGuardTest {

    private static final String APP = "com.confia.identity.web";

    private static Registered route(String method, String pattern) {
        return new Registered(APP, method, pattern);
    }

    private static Classified entry(String category, HttpMethod method, String pattern) {
        return new Classified(category, method, pattern);
    }

    // --- The decision over routes ---

    @Test
    void aRouteOnNoListIsNamedByMethodAndTemplate() {
        List<String> problems = RouteCoverageGuard.violations(
                List.of(route("GET", "/api/v1/students/{id}"), route("GET", "/api/v1/auth/x")),
                false, List.of(entry(RouteCoverageGuard.PUBLIC, HttpMethod.GET, "/api/v1/auth/x")));

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("GET").contains("/api/v1/students/{id}")
                .contains("is on no list");
    }

    @Test
    void theSamePathUnderAnotherMethodIsAnotherRoute() {
        List<String> problems = RouteCoverageGuard.violations(
                List.of(route("GET", "/a"), route("POST", "/a")), false,
                List.of(entry(RouteCoverageGuard.AUTHENTICATED, HttpMethod.GET, "/a")));

        assertThat(problems).singleElement().asString().contains("POST /a");
    }

    @Test
    void aTemplateIsComparedExactlyNotByPattern() {
        List<String> problems = RouteCoverageGuard.violations(
                List.of(route("GET", "/a/{id}")), false,
                List.of(entry(RouteCoverageGuard.PERMISSIONED, HttpMethod.GET, "/a/*")));

        assertThat(problems).anyMatch(text -> text.contains("GET /a/{id}"))
                .anyMatch(text -> text.contains("permission entry GET /a/* matches no registered route"));
    }

    @Test
    void anEntryOfThePermissionListThatNoRouteServesIsNamed() {
        List<String> problems = RouteCoverageGuard.violations(
                List.of(route("GET", "/api/v1/identity/roles")), false,
                List.of(entry(RouteCoverageGuard.PERMISSIONED, HttpMethod.GET,
                                "/api/v1/identity/roles"),
                        entry(RouteCoverageGuard.PERMISSIONED, HttpMethod.GET,
                                "/api/v1/identity/ghost")));

        assertThat(problems).singleElement().asString()
                .contains("GET /api/v1/identity/ghost").contains("matches no registered route");
    }

    @Test
    void aRouteOnTwoListsIsReported() {
        List<String> problems = RouteCoverageGuard.violations(List.of(route("GET", "/a")), false,
                List.of(entry(RouteCoverageGuard.PUBLIC, HttpMethod.GET, "/a"),
                        entry(RouteCoverageGuard.PERMISSIONED, HttpMethod.GET, "/a")));

        assertThat(problems).singleElement().asString().contains("GET /a")
                .contains("more than one list").contains("public").contains("permissioned");
    }

    @Test
    void aRouteWithoutAMethodIsReportedBecauseItMatchesEveryMethod() {
        List<String> problems = RouteCoverageGuard.violations(List.of(route(null, "/a")), false,
                List.of(entry(RouteCoverageGuard.PUBLIC, HttpMethod.GET, "/a")));

        assertThat(problems).singleElement().asString().contains("/a").contains("no HTTP method");
    }

    @Test
    void aFunctionalRouterAndAnEmptyScanAreReported() {
        assertThat(RouteCoverageGuard.violations(List.of(route("GET", "/a")), true,
                List.of(entry(RouteCoverageGuard.PUBLIC, HttpMethod.GET, "/a"))))
                .singleElement().asString().contains("functional router");
        assertThat(RouteCoverageGuard.violations(List.of(), false, List.of()))
                .singleElement().asString().contains("no route of the application was evaluated");
    }

    @Test
    void aHandlerOfALibraryIsOutsideTheScanAndAnEmptyScanIsNotRescuedByIt() {
        List<Registered> routes = List.of(new Registered("org.springdoc.webmvc.api", "GET",
                "/v3/api-docs.yaml"));

        assertThat(RouteCoverageGuard.violations(routes, false, List.of()))
                .as("the library route is not evaluated, so nothing was")
                .singleElement().asString().contains("no route of the application was evaluated");
        assertThat(RouteCoverageGuard.violations(
                List.of(route("GET", "/a"), routes.get(0)), false,
                List.of(entry(RouteCoverageGuard.AUTHENTICATED, HttpMethod.GET, "/a"))))
                .isEmpty();
    }

    @Test
    void everyRouteClassifiedExactlyOncePassesWithAPathVariable() {
        assertThat(RouteCoverageGuard.violations(
                List.of(route("GET", "/a"), route("GET", "/b/{id}"), route("POST", "/c")), false,
                List.of(entry(RouteCoverageGuard.PUBLIC, HttpMethod.GET, "/a"),
                        entry(RouteCoverageGuard.PERMISSIONED, HttpMethod.GET, "/b/{id}"),
                        entry(RouteCoverageGuard.RESTRICTED, HttpMethod.POST, "/c"))))
                .isEmpty();
    }

    // --- The start, over a real handler mapping ---

    @Test
    void theStartFailsNamingTheMethodAndTheRouteOfAnUnclassifiedControllerRoute()
            throws Exception {
        StaticListableBeanFactory beans = beansWith(mappingOf("/api/v1/unclassified"));

        RouteCoverageGuard guard = new RouteCoverageGuard(List.of(), beans);

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GET /api/v1/unclassified");
    }

    @Test
    void theStartFailsOnAnEntryWithoutARegisteredRoute() throws Exception {
        StaticListableBeanFactory beans = beansWith(mappingOf("/api/v1/identity/roles"));
        RouteCoverageGuard guard = new RouteCoverageGuard(List.of(
                entry(RouteCoverageGuard.PERMISSIONED, HttpMethod.GET, "/api/v1/identity/roles"),
                entry(RouteCoverageGuard.PERMISSIONED, HttpMethod.GET, "/api/v1/identity/ghost")),
                beans);

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GET /api/v1/identity/ghost");
    }

    @Test
    void theStartFailsOnAFunctionalRouter() throws Exception {
        StaticListableBeanFactory beans = beansWith(mappingOf("/a"));
        beans.addBean("functional", new RouterFunctionMapping(RouterFunctions.route()
                .GET("/hidden", request -> ServerResponse.ok().build()).build()));
        RouteCoverageGuard guard = new RouteCoverageGuard(
                List.of(entry(RouteCoverageGuard.PUBLIC, HttpMethod.GET, "/a")), beans);

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .hasMessageContaining("functional router");
    }

    @Test
    void theStartPassesWhenTheRegisteredRouteIsClassified() throws Exception {
        StaticListableBeanFactory beans = beansWith(mappingOf("/a"));
        RouteCoverageGuard guard = new RouteCoverageGuard(
                List.of(entry(RouteCoverageGuard.PUBLIC, HttpMethod.GET, "/a")), beans);

        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    private static StaticListableBeanFactory beansWith(RequestMappingHandlerMapping mapping) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("mapping", mapping);
        return beans;
    }

    private static RequestMappingHandlerMapping mappingOf(String pattern) throws Exception {
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        GenericWebApplicationContext context = new GenericWebApplicationContext(new MockServletContext());
        context.refresh();
        mapping.setApplicationContext(context);
        mapping.afterPropertiesSet();
        Method handler = SomeController.class.getDeclaredMethod("handle");
        mapping.registerMapping(RequestMappingInfo.paths(pattern)
                .methods(org.springframework.web.bind.annotation.RequestMethod.GET).build(),
                new SomeController(), handler);
        return mapping;
    }

    /** A controller of the application package, registered by hand. */
    static final class SomeController {

        public String handle() {
            return "ok";
        }
    }
}
