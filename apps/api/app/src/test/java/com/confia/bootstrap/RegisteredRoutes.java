package com.confia.bootstrap;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/**
 * Every route a started process serves, enumerated from its handler mappings (web-edge-foundations
 * design.md, decision 21): the {@code RequestMappingInfoHandlerMapping}s (controllers), the {@code
 * AbstractUrlHandlerMapping}s (resource handlers and redirects) and the {@code
 * RouterFunctionMapping}s (functional routes). A route is a method, {@value #ANY_METHOD} when the
 * mapping does not restrict it, and a pattern.
 *
 * <p>A functional router is not expanded into its predicates: if one holds any route at all, it
 * contributes the single route {@value #FUNCTIONAL_ROUTER}, so the presence of an unlisted
 * functional route is always visible to the allow-list and the snapshot, by a name no controller
 * pattern can have.
 */
final class RegisteredRoutes {

    static final String ANY_METHOD = "ANY";
    static final String FUNCTIONAL_ROUTER = "(functional router)";

    /** One route; ordered by pattern, then method, so every listing is deterministic. */
    record Route(String method, String pattern) implements Comparable<Route> {

        private static final Comparator<Route> ORDER =
                Comparator.comparing(Route::pattern).thenComparing(Route::method);

        @Override
        public int compareTo(Route other) {
            return ORDER.compare(this, other);
        }

        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    private RegisteredRoutes() {
    }

    /** The routes of {@code context}, sorted and without repetitions. */
    static List<Route> of(ApplicationContext context) {
        Set<Route> routes = new TreeSet<>();
        mappings(context, RequestMappingInfoHandlerMapping.class)
                .forEach(mapping -> mapping.getHandlerMethods().keySet()
                        .forEach(info -> add(routes, info)));
        mappings(context, AbstractUrlHandlerMapping.class)
                .forEach(mapping -> mapping.getHandlerMap().keySet()
                        .forEach(pattern -> routes.add(new Route(ANY_METHOD, pattern))));
        mappings(context, RouterFunctionMapping.class).forEach(mapping -> {
            if (mapping.getRouterFunction() != null) {
                routes.add(new Route(ANY_METHOD, FUNCTIONAL_ROUTER));
            }
        });
        return List.copyOf(routes);
    }

    /** The beans of a type in the context and in its ancestors, as a child context sees them. */
    private static <T> Collection<T> mappings(ApplicationContext context, Class<T> type) {
        return BeanFactoryUtils.beansOfTypeIncludingAncestors(context, type).values();
    }

    private static void add(Set<Route> routes, RequestMappingInfo info) {
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        for (String pattern : info.getPatternValues()) {
            if (methods.isEmpty()) {
                routes.add(new Route(ANY_METHOD, pattern));
            }
            methods.forEach(method -> routes.add(new Route(method.name(), pattern)));
        }
    }
}
