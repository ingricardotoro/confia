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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
public class AuditScopeExclusionInventoryTest {

    private static final Set<String> SCHEDULING_PACKAGE_PREFIXES =
            Set.of("org.springframework.scheduling.", "com.github.kagkarlsson.");

    private static final Set<Class<? extends Annotation>> MAPPING_ANNOTATIONS =
            Set.of(RequestMapping.class, GetMapping.class, PostMapping.class, PutMapping.class,
                    DeleteMapping.class, PatchMapping.class);

    /**
     * db-scheduler is admitted, and only from the package that confines it
     * (background-jobs-with-db-scheduler design.md decisions 3 and 21; ADR-0016 rule 1). The full
     * confinement rule, with its fixtures, is {@code DbSchedulerConfinementTest} (task 2.5); this
     * inventory keeps denying every other scheduling API and db-scheduler outside that package.
     */
    public static boolean isTheConfinedUseOfDbScheduler(String classPackage, String targetPackage) {
        return targetPackage.startsWith("com.github.kagkarlsson.")
                && (classPackage + ".").startsWith("com.confia.shared.jobs.infrastructure.");
    }

    @Test
    void theConfinementExemptionAdmitsOnlyDbSchedulerFromTheJobsInfrastructurePackage() {
        assertThat(isTheConfinedUseOfDbScheduler("com.confia.shared.jobs.infrastructure.wiring",
                "com.github.kagkarlsson.scheduler.")).isTrue();
        assertThat(isTheConfinedUseOfDbScheduler("com.confia.identity.application",
                "com.github.kagkarlsson.scheduler.")).isFalse();
        assertThat(isTheConfinedUseOfDbScheduler("com.confia.bootstrap.admin",
                "com.github.kagkarlsson.scheduler.")).isFalse();
        assertThat(isTheConfinedUseOfDbScheduler("com.confia.shared.jobs.infrastructure",
                "org.springframework.scheduling.annotation.")).isFalse();
    }

    @Test
    void noProductionClassDependsOnATaskSchedulingTypeAndNoScheduledMethodExists() {
        JavaClasses classes = assertNonEmptyProductionClasses();

        for (JavaClass javaClass : classes) {
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                String targetPackage = dependency.getTargetClass().getPackageName() + ".";
                boolean dependsOnScheduling = SCHEDULING_PACKAGE_PREFIXES.stream()
                        .anyMatch(targetPackage::startsWith)
                        && !isTheConfinedUseOfDbScheduler(javaClass.getPackageName(), targetPackage);
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

    /**
     * Scenario BI97 (background-jobs-with-db-scheduler specs/build-integrity/spec.md): the absence
     * this test used to assert is inverted. {@code scheduled_tasks} is db-scheduler's table
     * (ADR-0016) and appears in exactly one migration, {@code V10}; any other migration naming it,
     * and any table of another scheduling library, still breaks the build.
     */
    @Test
    void scheduledTasksAppearsOnlyInV10AndNoOtherSchedulingTableExists() {
        Map<String, String> migrations = migrationSqlByFile();
        assertThat(String.join("", migrations.values()))
                .as("the base scan must be the real migration SQL text, or the checks below "
                        + "would pass vacuously")
                .contains("shared_audit_log")
                .contains("organization_institution");
        assertThat(migrations).as("V10 is the migration of the scheduler table")
                .containsKey("V10__create_scheduled_tasks.sql");
        assertThat(migrations.get("V10__create_scheduled_tasks.sql").toLowerCase(Locale.ROOT))
                .contains("create table scheduled_tasks");

        assertThat(schedulingTableViolations(migrations)).isEmpty();
    }

    @Test
    void aMigrationOtherThanV10NamingScheduledTasksIsRejected() {
        Map<String, String> fixture = new TreeMap<>(migrationSqlByFile());
        fixture.put("V99__alter_scheduler.sql", "ALTER TABLE scheduled_tasks ADD COLUMN x integer;");

        assertThat(schedulingTableViolations(fixture)).singleElement().asString()
                .contains("V99__alter_scheduler.sql", "scheduled_tasks");
    }

    @Test
    void aTableOfAnotherSchedulingLibraryIsRejected() {
        for (String table : List.of("qrtz_triggers", "jobrunr_jobs", "shedlock", "batch_job_instance")) {
            Map<String, String> fixture = new TreeMap<>(migrationSqlByFile());
            fixture.put("V99__other_scheduler.sql", "CREATE TABLE " + table + " (id integer);");

            assertThat(schedulingTableViolations(fixture)).as(table).singleElement().asString()
                    .contains("V99__other_scheduler.sql", table);
        }
    }

    private static final Pattern OTHER_SCHEDULING_TABLE =
            Pattern.compile("\\b(qrtz_\\w*|jobrunr_\\w*|shedlock|batch_job_\\w*)\\b");

    private static List<String> schedulingTableViolations(Map<String, String> migrations) {
        List<String> violations = new ArrayList<>();
        migrations.forEach((file, sql) -> {
            String text = sql.toLowerCase(Locale.ROOT);
            if (text.contains("scheduled_tasks") && !file.startsWith("V10__")) {
                violations.add(file + " names scheduled_tasks but only V10 may");
            }
            Matcher other = OTHER_SCHEDULING_TABLE.matcher(text);
            while (other.find()) {
                violations.add(file + " names the scheduling table " + other.group());
            }
        });
        return violations;
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
     * Every {@code .sql} file under the real {@code db/migration} classpath directory, by file name
     * — never a hand-written list of migration names, so a future {@code V11} is included
     * automatically.
     */
    private static Map<String, String> migrationSqlByFile() {
        URL directoryUrl =
                AuditScopeExclusionInventoryTest.class.getClassLoader().getResource("db/migration");
        if (directoryUrl == null) {
            throw new IllegalStateException("db/migration classpath directory not found");
        }
        try {
            Path directory = Path.of(directoryUrl.toURI());
            Map<String, String> byFile = new TreeMap<>();
            try (var sqlFiles = Files.list(directory)) {
                for (Path file : sqlFiles.filter(p -> p.toString().endsWith(".sql")).toList()) {
                    byFile.put(file.getFileName().toString(),
                            Files.readString(file, StandardCharsets.UTF_8));
                }
            }
            return byFile;
        } catch (URISyntaxException e) {
            throw new IllegalStateException("malformed db/migration classpath URL", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
