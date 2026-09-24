package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.Locale;
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

    /**
     * (a) No production class in a {@code ..web..} package depends on {@code
     * com.confia.shared.security}, and no production class carries the {@code Idempotency-Key}
     * header literal — both true today only because no production {@code web} layer exists yet
     * (brecha con destino: cambio 7).
     */
    @Test
    void noWebPackageClassDependsOnSharedSecurityAndNoProductionClassMentionsTheHeaderLiteral()
            throws IOException {
        JavaClasses classes = assertNonEmptyProductionClasses();

        for (JavaClass javaClass : classes) {
            if (!("." + javaClass.getPackageName() + ".").contains(".web.")) {
                continue;
            }
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                assertThat(dependency.getTargetClass().getPackageName())
                        .as("%s resides in a web package and must not depend on "
                                        + "com.confia.shared.security — no controller exists yet "
                                        + "(brecha con destino: cambio 7)",
                                javaClass.getFullName())
                        .doesNotStartWith("com.confia.shared.security");
            }
        }

        String bytecodeText = concatenatedProductionBytecodeText(classes);
        assertThat(bytecodeText)
                .as("no production class must carry the Idempotency-Key header literal yet: no "
                        + "web layer of production code exists in this cut (brecha con destino: "
                        + "cambio 7)")
                .doesNotContain("Idempotency-Key");
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
