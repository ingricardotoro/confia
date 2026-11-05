package com.confia.shared.web.authorization;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Stops the start of the administrative process when a route of the application belongs to none of the
 * four classes of the chain, or when the list of permission routes names a route nobody serves
 * (rbac-permission-matrix-and-audit-integration design.md D13; scenarios AZ34 to AZ36). It runs once
 * every singleton exists, which is before the web server accepts a connection, like {@code
 * RateLimitPolicyCheck}: an unclassified route is a failed start naming its method and template, never
 * a route that the final denial happens to close.
 *
 * <p>It fails when:
 *
 * <ul>
 *   <li>a route of a handler of {@code com.confia} does not match, by method and exact template, one
 *       entry of the public, authenticated, restricted or permission class, or declares no method;
 *   <li>an entry of the permission list matches no registered route;
 *   <li>a route is in two classes;
 *   <li>a functional router exists, whose routes cannot be enumerated;
 *   <li>it evaluated no route at all.
 * </ul>
 *
 * Handlers of libraries are outside the scan: the documentation routes of springdoc are not on any
 * list in the profiles that register them, and the final denial closes them.
 */
public final class RouteCoverageGuard implements SmartInitializingSingleton {

    /** The classes of route, named in the messages. */
    public static final String PUBLIC = "public";
    public static final String AUTHENTICATED = "authenticated";
    public static final String RESTRICTED = "restricted";
    public static final String PERMISSIONED = "permissioned";

    private static final String APPLICATION_PACKAGE = "com.confia";

    /** One entry of one of the four lists. */
    public record Classified(String category, HttpMethod method, String pattern) {

        public Classified {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(pattern, "pattern");
        }
    }

    /** One route a handler mapping registers: a method, or {@code null} when none is declared. */
    public record Registered(String beanPackage, String method, String pattern) {
    }

    private final List<Classified> classified;
    private final ListableBeanFactory beans;

    public RouteCoverageGuard(Collection<Classified> classified, ListableBeanFactory beans) {
        this.classified = List.copyOf(classified);
        this.beans = Objects.requireNonNull(beans, "beans");
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<Registered> routes = new ArrayList<>();
        beans.getBeansOfType(RequestMappingHandlerMapping.class).values()
                .forEach(mapping -> mapping.getHandlerMethods()
                        .forEach((info, handler) -> routes.addAll(routesOf(info, handler))));
        boolean functionalRouter = beans.getBeansOfType(RouterFunctionMapping.class).values()
                .stream().anyMatch(mapping -> mapping.getRouterFunction() != null);
        List<String> problems = violations(routes, functionalRouter, classified);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems));
        }
    }

    private static List<Registered> routesOf(RequestMappingInfo info, HandlerMethod handler) {
        String beanPackage = handler.getBeanType().getPackageName();
        List<Registered> routes = new ArrayList<>();
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        for (String pattern : info.getPatternValues()) {
            if (methods.isEmpty()) {
                routes.add(new Registered(beanPackage, null, pattern));
            }
            methods.forEach(method -> routes.add(new Registered(beanPackage, method.name(), pattern)));
        }
        return routes;
    }

    /**
     * One sentence per defect, sorted and without repetitions; empty when every route is classified
     * exactly once and every permission entry is served.
     */
    static List<String> violations(Collection<Registered> routes, boolean functionalRouter,
            Collection<Classified> classified) {
        Set<String> problems = new TreeSet<>();
        List<Registered> evaluated = routes.stream().filter(RouteCoverageGuard::isApplication)
                .toList();
        if (evaluated.isEmpty()) {
            problems.add("no route of the application was evaluated");
        }
        if (functionalRouter) {
            problems.add("a functional router is registered and its routes cannot be enumerated");
        }
        for (Registered route : evaluated) {
            if (route.method() == null) {
                problems.add("route " + route.pattern() + " declares no HTTP method");
                continue;
            }
            Set<String> categories = new LinkedHashSet<>();
            classified.stream().filter(entry -> entry.method().name().equals(route.method())
                    && entry.pattern().equals(route.pattern()))
                    .forEach(entry -> categories.add(entry.category()));
            if (categories.isEmpty()) {
                problems.add("route " + route.method() + " " + route.pattern()
                        + " is on no list (public, authenticated, restricted or permissioned)");
            } else if (categories.size() > 1) {
                problems.add("route " + route.method() + " " + route.pattern()
                        + " is on more than one list: " + categories);
            }
        }
        for (Classified entry : classified) {
            if (!PERMISSIONED.equals(entry.category())) {
                continue;
            }
            boolean served = routes.stream().anyMatch(route -> entry.method().name()
                    .equals(route.method()) && entry.pattern().equals(route.pattern()));
            if (!served) {
                problems.add("permission entry " + entry.method() + " " + entry.pattern()
                        + " matches no registered route");
            }
        }
        return List.copyOf(problems);
    }

    private static boolean isApplication(Registered route) {
        return route.beanPackage().equals(APPLICATION_PACKAGE)
                || route.beanPackage().startsWith(APPLICATION_PACKAGE + ".");
    }
}
