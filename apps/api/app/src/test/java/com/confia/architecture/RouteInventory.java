package com.confia.architecture;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The routes the handlers of a set of compiled classes declare, as {@code "METHOD /full/path"}, and
 * the patterns of the routes that this change deliberately leaves out (specs/identity, scenarios I216
 * and BI52). It reads the annotations of the handlers ({@code @GetMapping} and its siblings, joined to
 * the {@code @RequestMapping} of the class), so a route added by a controller is seen without starting
 * a process. Test support, never production code.
 */
public final class RouteInventory {

    private static final List<String> VERBS = List.of("Get", "Post", "Put", "Patch", "Delete");

    /** A path that recovers or resets a password, whatever the verb and the spelling around it. */
    private static final Pattern PASSWORD_RECOVERY = Pattern.compile(
            "(?i).*(password|passwd|recover|forgot|reset).*");

    private RouteInventory() {
    }

    /** Every route of the handlers in {@code classes}, in the order the classes are scanned. */
    public static List<String> routesOf(JavaClasses classes) {
        List<String> routes = new ArrayList<>();
        for (JavaClass type : classes) {
            String base = type.tryGetAnnotationOfType("org.springframework.web.bind.annotation.RequestMapping")
                    .map(RouteInventory::firstPath).orElse("");
            for (JavaMethod method : type.getMethods()) {
                for (String verb : VERBS) {
                    method.tryGetAnnotationOfType("org.springframework.web.bind.annotation." + verb
                            + "Mapping").ifPresent(annotation -> routes.add(
                                    verb.toUpperCase(Locale.ROOT) + " " + base
                                            + firstPath(annotation)));
                }
            }
        }
        return routes;
    }

    /** The routes among {@code routes} (as {@code "METHOD path"}) that look like password recovery. */
    public static List<String> passwordRecoveryRoutes(List<String> routes) {
        return routes.stream().filter(route -> PASSWORD_RECOVERY.matcher(route).matches()).toList();
    }

    private static String firstPath(JavaAnnotation<?> annotation) {
        for (String property : List.of("path", "value")) {
            Object value = annotation.get(property).orElse(null);
            if (value instanceof Object[] paths && paths.length > 0) {
                return String.valueOf(paths[0]);
            }
        }
        return "";
    }
}
