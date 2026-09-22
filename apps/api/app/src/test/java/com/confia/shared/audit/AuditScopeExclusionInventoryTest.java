package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Two inventories over what this change explicitly does NOT build yet
 * (specs/audit-trail/spec.md, "Alcance de la verificación de cadena sin programación recurrente"
 * and "Criterio de fila de la bitácora limitado a la institución, sin filtro por {@code
 * audit:read}"). No Docker: static inventory over the compiled class tree and this repository's
 * own migration SQL text, never a live PostgreSQL connection.
 *
 * <p>Both inventories iterate a demonstrably non-empty base set first, the same "membership, not
 * presence" discipline {@code MultiTenantSchemaIT} uses (task brief: "asegúrate de que el conjunto
 * sobre el que iteran no está vacío"): a loop over an accidentally empty collection would let an
 * absence assertion pass without ever checking anything real.
 */
class AuditScopeExclusionInventoryTest {

    private static final Set<String> SCHEDULING_PACKAGE_PREFIXES =
            Set.of("org.springframework.scheduling.", "com.github.kagkarlsson.");

    private static final Set<Class<? extends Annotation>> MAPPING_ANNOTATIONS =
            Set.of(RequestMapping.class, GetMapping.class, PostMapping.class, PutMapping.class,
                    DeleteMapping.class, PatchMapping.class);

    @Test
    void noProductionClassDependsOnATaskSchedulingTypeAndNoScheduledMethodExists() {
        JavaClasses classes = assertNonEmptyProductionClasses();

        for (JavaClass javaClass : classes) {
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                String targetPackage = dependency.getTargetClass().getPackageName() + ".";
                boolean dependsOnScheduling = SCHEDULING_PACKAGE_PREFIXES.stream()
                        .anyMatch(targetPackage::startsWith);
                assertThat(dependsOnScheduling)
                        .as("%s must not depend on a task-scheduling type (%s): db-scheduler "
                                        + "(ADR-0016) is cambio 9's own responsibility, not this cut's",
                                javaClass.getFullName(), dependency.getTargetClass().getFullName())
                        .isFalse();
            }
            for (JavaMethod method : javaClass.getMethods()) {
                assertThat(method.isAnnotatedWith(Scheduled.class))
                        .as("%s must not carry @Scheduled: no scheduling infrastructure is wired "
                                + "in this cut", method.getFullName())
                        .isFalse();
            }
        }
    }

    @Test
    void scheduledTasksDoesNotAppearInTheDeliveredSchemasMigrations() {
        String migrationSql = concatenatedMigrationSql();
        assertThat(migrationSql)
                .as("the base scan must be the real migration SQL text, or the absence check "
                        + "below would pass vacuously")
                .contains("shared_audit_log")
                .contains("organization_institution");

        assertThat(migrationSql.toLowerCase(Locale.ROOT))
                .as("no delivered migration creates scheduled_tasks yet: db-scheduler (ADR-0016) "
                        + "arrives with cambio 9, not this cut")
                .doesNotContain("scheduled_tasks");
    }

    @Test
    void noWebPackageClassDependsOnTheAuditModule() {
        JavaClasses classes = assertNonEmptyProductionClasses();

        for (JavaClass javaClass : classes) {
            if (!("." + javaClass.getPackageName() + ".").contains(".web.")) {
                continue;
            }
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                assertThat(dependency.getTargetClass().getPackageName())
                        .as("%s resides in a web package and must not depend on "
                                        + "com.confia.shared.audit — no controller exposes the ledger "
                                        + "yet (cambios 7 y 8 own that)",
                                javaClass.getFullName())
                        .doesNotStartWith("com.confia.shared.audit");
            }
        }
    }

    @Test
    void noOpenApiRouteOfTheAdministrativeApplicationExposesTheAuditLog() {
        JavaClasses classes = assertNonEmptyProductionClasses();

        for (JavaClass javaClass : classes) {
            for (String path : routePathsOf(javaClass)) {
                assertThat(path.toLowerCase(Locale.ROOT))
                        .as("route '%s' of %s must not expose shared_audit_log with the audit:read "
                                        + "permission — cambios 7 y 8 own the controller that adds it",
                                path, javaClass.getFullName())
                        .doesNotContain("audit");
            }
        }
    }

    /**
     * Proves the loop below is real (task brief's anti-vacuous-test rule), the same way {@code
     * MultiTenantSchemaIT#everyBusinessTableNameCarriesItsOwnerModulesPrefixExceptTheClosedCatalogue}
     * proves its own module set is non-empty before an absence-shaped loop.
     */
    private static JavaClasses assertNonEmptyProductionClasses() {
        JavaClasses classes = productionClasses();
        assertThat(classes.stream().map(JavaClass::getFullName).toList())
                .as("the scanned production class tree must be real and populated, or every "
                        + "absence check in this class would pass vacuously")
                .contains("com.confia.shared.security.TransactionRunner");
        return classes;
    }

    private static List<String> routePathsOf(JavaClass javaClass) {
        List<String> paths = new ArrayList<>();
        collectRoutePaths(javaClass, paths);
        for (JavaMethod method : javaClass.getMethods()) {
            collectRoutePaths(method, paths);
        }
        return paths;
    }

    /** Every path a springdoc-generated OpenAPI document would read from the same annotations. */
    private static void collectRoutePaths(HasAnnotations<?> annotated, List<String> paths) {
        for (Class<? extends Annotation> annotationType : MAPPING_ANNOTATIONS) {
            annotated.tryGetAnnotationOfType(annotationType)
                    .ifPresent(annotation -> paths.addAll(pathValuesOf(annotationType, annotation)));
        }
    }

    private static List<String> pathValuesOf(Class<? extends Annotation> annotationType,
            Annotation annotation) {
        try {
            Object value = annotationType.getMethod("value").invoke(annotation);
            return value instanceof String[] values ? Arrays.asList(values) : List.of();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not read value() of " + annotationType, e);
        }
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.confia");
    }

    /**
     * Every {@code .sql} file under the real {@code db/migration} classpath directory, concatenated
     * — never a hand-written list of migration names, so a future {@code V4} is included
     * automatically.
     */
    private static String concatenatedMigrationSql() {
        URL directoryUrl =
                AuditScopeExclusionInventoryTest.class.getClassLoader().getResource("db/migration");
        if (directoryUrl == null) {
            throw new IllegalStateException("db/migration classpath directory not found");
        }
        try {
            Path directory = Path.of(directoryUrl.toURI());
            StringBuilder builder = new StringBuilder();
            try (var sqlFiles = Files.list(directory)) {
                for (Path file : sqlFiles.filter(p -> p.toString().endsWith(".sql")).toList()) {
                    builder.append(Files.readString(file, StandardCharsets.UTF_8));
                }
            }
            return builder.toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("malformed db/migration classpath URL", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
