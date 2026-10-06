package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The three named-owner exclusions this change deliberately leaves open (design.md, decision 11;
 * specs/build-integrity/spec.md, requirements "Ausencia de superficie HTTP...", "Ausencia de purga
 * física..." and "Ausencia de acceso del portal..."). PR C3.
 *
 * <p>Static inventories over the compiled class tree and the real migration text, never a live
 * PostgreSQL connection — the running proof that a caducated row survives lives in {@link
 * IdempotencyExpiryIT} (task 6.3), which needs Docker; this class does not.
 *
 * <p>Each assertion proves its own base set is real first — "membership, not presence", the same
 * discipline {@code MultiTenantSchemaIT} and {@code AuditScopeExclusionInventoryTest} already apply
 * — because a loop over an accidentally empty collection would let an absence assertion pass
 * without ever checking anything real (design.md, decision 11's own warning, citing changes 4 and
 * 5's precedent).
 */
class IdempotencyScopeExclusionInventoryTest {

    /** The package of the HTTP idempotency mechanism, the one web package that may use the core. */
    private static final String EDGE_PACKAGE = "com.confia.shared.web.idempotency";

    /**
     * The only types of {@code com.confia.shared.security} that a class in a {@code ..web..} package
     * may depend on, by full class name. Add the next one here (2.3c added {@code RequestOrigin}; 3.2b
     * added the limiter and its answers, which the interceptor of the edge uses; 3.2c added
     * {@code RateLimitPolicy}, a plain value that the properties and the throttling configuration
     * hand to the limiter. {@code InMemoryRateLimiter} stays out on purpose: a shared.security
     * configuration builds it and exposes only the port, so the web layer never names the
     * implementation).
     */
    private static final Set<String> WEB_MAY_DEPEND_ON_SHARED_SECURITY = Set.of(
            "com.confia.shared.security.ClientAddress", "com.confia.shared.security.ClientKey",
            "com.confia.shared.security.RequestOrigin", "com.confia.shared.security.RateLimiter",
            "com.confia.shared.security.RateLimitDecision",
            "com.confia.shared.security.RateLimitDecision$Admitted",
            "com.confia.shared.security.RateLimitDecision$Limited",
            "com.confia.shared.security.RateLimitDecision$CapacityExhausted",
            "com.confia.shared.security.RateLimitPolicy");

    private static final String EXECUTOR = "com.confia.shared.security.IdempotentExecutor";

    /** The one class outside the mechanism that wires it, and so names the executor it needs. */
    private static final String EDGE_CONFIGURATION =
            "com.confia.shared.web.edge.IdempotencyEdgeConfiguration";

    /**
     * What only the HTTP idempotency mechanism ({@value #EDGE_PACKAGE}) may add to the list above
     * (5.1): the executor it calls, what it returns, the key it builds and the context it carries.
     * No other web class may name any of them, so a controller reaches idempotency only through the
     * handler of the edge and never through the component directly; the one other class that may name
     * the executor is {@link #EDGE_CONFIGURATION}, which hands it to the handler. The two exceptions of the
     * component are not here: they are domain exceptions the one translator already answers.
     */
    private static final Set<String> IDEMPOTENCY_EDGE_MAY_ALSO_DEPEND_ON = Set.of(
            EXECUTOR, "com.confia.shared.security.IdempotentOutcome",
            "com.confia.shared.security.IdempotentOutcome$Executed",
            "com.confia.shared.security.IdempotentOutcome$Replayed",
            "com.confia.shared.security.IdempotentResponse",
            "com.confia.shared.security.IdempotencyKey",
            "com.confia.shared.security.SecurityContext");

    /**
     * (a) A production class in a {@code ..web..} package may depend on {@code
     * com.confia.shared.security} only through {@link #WEB_MAY_DEPEND_ON_SHARED_SECURITY}, plus
     * {@link #IDEMPOTENCY_EDGE_MAY_ALSO_DEPEND_ON} for the classes of {@value #EDGE_PACKAGE} alone;
     * and no production class outside that package carries the {@code Idempotency-Key} header
     * literal. Before 5.1 no production class could, because no production idempotent endpoint
     * existed (brecha con destino: cambio 7); the web edge of idempotency, delivered by PR 13,
     * retires that blanket absence and keeps the part that still holds: the mechanism has one home.
     *
     * <p>This used to forbid every dependency of a web class on the whole package, and was then
     * narrowed to a denylist of idempotency names. A denylist by simple name misses a nested class
     * (whose simple name drops the outer one) and any idempotency class added under another name,
     * and let the web layer reach {@code TransactionRunner} and {@code SecurityContext} unnoticed.
     * It is now an allowlist by full class name: the request filter of web-edge-foundations needs the
     * address types and nothing else, and every other type of the package, including every
     * idempotency type and every nested one, is rejected until a change names it here.
     */
    @Test
    void noWebClassOutsideTheIdempotencyEdgeDependsOnSharedSecurityBeyondTheAllowlistOrMentionsTheHeaderLiteral()
            throws IOException {
        JavaClasses classes = assertNonEmptyProductionClasses();

        for (JavaClass javaClass : classes) {
            if (!("." + javaClass.getPackageName() + ".").contains(".web.")) {
                continue;
            }
            Set<String> allowed = new HashSet<>(WEB_MAY_DEPEND_ON_SHARED_SECURITY);
            if (isInEdgePackage(javaClass)) {
                allowed.addAll(IDEMPOTENCY_EDGE_MAY_ALSO_DEPEND_ON);
            }
            if (javaClass.getName().equals(EDGE_CONFIGURATION)) {
                allowed.add(EXECUTOR);
            }
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                boolean outsideTheAllowlist = target.getPackageName().equals("com.confia.shared.security")
                        && !allowed.contains(target.getName());
                assertThat(outsideTheAllowlist)
                        .as("%s resides in a web package and depends on %s, which is not one of %s",
                                javaClass.getFullName(), target.getFullName(), allowed)
                        .isFalse();
            }
        }

        JavaClasses edge = classes.that(DescribedPredicate.describe("are the idempotency edge",
                IdempotencyScopeExclusionInventoryTest::isInEdgePackage));
        assertThat(concatenatedProductionBytecodeText(edge))
                .as("non-vacuous: the edge is where the Idempotency-Key literal lives, or the "
                        + "check below would look for it in the wrong place")
                .contains("Idempotency-Key");
        JavaClasses everythingElse = classes.that(DescribedPredicate.describe(
                "are not the idempotency edge",
                javaClass -> !isInEdgePackage(javaClass)));
        assertThat(concatenatedProductionBytecodeText(everythingElse))
                .as("no production class outside %s may carry the Idempotency-Key header literal",
                        EDGE_PACKAGE)
                .doesNotContain("Idempotency-Key");
    }

    private static boolean isInEdgePackage(JavaClass javaClass) {
        return javaClass.getPackageName().equals(EDGE_PACKAGE);
    }

    /**
     * (b) The concatenated text of every delivered migration mentions {@code
     * shared_idempotency_key} and none of them declares a physical purge over it (brecha con
     * destino: cambio 9) — re-affirms, from the text inventory, what {@link IdempotencyExpiryIT}
     * (task 6.3) already demonstrated in execution: a caducated row is never deleted.
     */
    @Test
    void noDeliveredMigrationPurgesTheIdempotencyTable() {
        String migrationSql = concatenatedMigrationSql();
        assertThat(migrationSql)
                .as("the base scan must be the real migration SQL text, or the absence check below "
                        + "would pass vacuously")
                .contains("shared_idempotency_key");

        assertThat(migrationSql.toLowerCase(Locale.ROOT))
                .as("no delivered migration deletes rows from shared_idempotency_key: physical "
                        + "purge is cambio 9's own responsibility (docs/03-seguridad.md section "
                        + "6.1 grants no DELETE on this table to any application role either)")
                .doesNotContain("delete from shared_idempotency_key");
    }

    /**
     * (c) {@code confia_portal_app}'s absence of privilege on {@code shared_idempotency_key} is
     * already a real, named test in {@code RolePrivilegeMatrixIT} (task 1.1) — this inventory only
     * confirms the row exists in the matrix, without repeating real SQL statements.
     */
    @Test
    void confiaPortalAppsAbsenceOfPrivilegeIsAlreadyCoveredByRolePrivilegeMatrixIT()
            throws ClassNotFoundException {
        Class<?> matrixClass = Class.forName("com.confia.schema.RolePrivilegeMatrixIT");
        List<String> testMethodNames = Arrays.stream(matrixClass.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Test.class))
                .map(Method::getName)
                .toList();

        assertThat(testMethodNames)
                .as("RolePrivilegeMatrixIT must declare at least one @Test method, or this "
                        + "inventory would pass vacuously")
                .isNotEmpty();

        assertThat(testMethodNames)
                .as("confia_portal_app's absence of privilege on shared_idempotency_key must "
                        + "already be a real, named test in RolePrivilegeMatrixIT (brecha con "
                        + "destino: F3/F4)")
                .anyMatch(name -> name.toLowerCase(Locale.ROOT).contains("portalapp")
                        && name.toLowerCase(Locale.ROOT).contains("idempotencykeytable"));
    }

    /**
     * Proves the loops above are real, the same way {@code
     * AuditScopeExclusionInventoryTest#assertNonEmptyProductionClasses} proves its own set first.
     */
    private static JavaClasses assertNonEmptyProductionClasses() {
        JavaClasses classes = productionClasses();
        assertThat(classes.stream().map(JavaClass::getFullName).toList())
                .as("the scanned production class tree must be real and populated, or every "
                        + "absence check in this class would pass vacuously")
                .contains("com.confia.shared.security.IdempotentExecutor");
        return classes;
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.confia");
    }

    /**
     * Reads the real, compiled {@code .class} bytes of exactly the production classes {@code
     * classes} already names — never a raw classpath-root directory walk, which would be ambiguous
     * between {@code target/classes} and {@code target/test-classes} for any class sharing this
     * same package. A {@code String} literal embedded in a method body survives untouched in the
     * class file's UTF-8 constant pool, so decoding the raw bytes as ISO-8859-1 (lossless,
     * byte-for-byte) and searching the decoded text for the ASCII header literal is sufficient —
     * exactly the same "concatenate real artifacts, then search the text" shape {@code
     * AuditScopeExclusionInventoryTest#concatenatedMigrationSql} already established for migration
     * SQL.
     */
    private static String concatenatedProductionBytecodeText(JavaClasses classes)
            throws IOException {
        StringBuilder builder = new StringBuilder();
        for (JavaClass javaClass : classes) {
            String resourceName = javaClass.getFullName().replace('.', '/') + ".class";
            URL resource = IdempotencyScopeExclusionInventoryTest.class.getClassLoader()
                    .getResource(resourceName);
            if (resource == null) {
                continue;
            }
            try (InputStream in = resource.openStream()) {
                builder.append(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
            }
        }
        return builder.toString();
    }

    /**
     * Every {@code .sql} file under the real {@code db/migration} classpath directory, concatenated
     * — the identical technique {@code AuditScopeExclusionInventoryTest#concatenatedMigrationSql}
     * uses, duplicated here rather than shared across packages because that helper is package-
     * private in {@code com.confia.shared.audit}.
     */
    private static String concatenatedMigrationSql() {
        URL directoryUrl = IdempotencyScopeExclusionInventoryTest.class.getClassLoader()
                .getResource("db/migration");
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
