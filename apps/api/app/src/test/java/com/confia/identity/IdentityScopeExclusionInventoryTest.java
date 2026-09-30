package com.confia.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.application.AuthenticationCommand;
import com.confia.identity.domain.BackoffPolicy;
import com.confia.identity.domain.BackoffState;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.crypto.DataEncryptionKeyRepository;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Five non-empty-set exclusions, following {@code AuditScopeExclusionInventoryTest}'s own
 * discipline (specs/identity/spec.md, requirements "Ausencia de la dimensión por dirección IP...",
 * "Ausencia de verificación contra contraseñas comprometidas...", "Ausencia de calibración de
 * Argon2id en servidor real", "Ausencia de prueba de extremo a extremo con Playwright..." and, from
 * column-encryption-and-mfa-totp, "Ausencia de ejecución real de la rotación de la llave de
 * datos...") plus
 * one more inventory task 4.3 names alongside them: no class of {@code com.confia.identity..}
 * depends on any logging framework. Each exclusion iterates a demonstrably non-empty base set
 * first, so an absence assertion can never pass by accident over an empty collection.
 *
 * <p><b>No Docker</b> (task 4.3): a static inventory over class trees, reflection, {@link
 * BackoffPolicy}'s own pure domain logic and the migration-free file tree — never a real
 * PostgreSQL, which real end-to-end behavior against real Argon2id is {@code
 * AuthenticateWithPasswordIT}'s own job (task 4.1).
 */
class IdentityScopeExclusionInventoryTest {

    @Test
    void noIpAddressDimensionAppliesYetAcrossTenSimulatedAttemptsAgainstFiveAccounts() {
        BackoffPolicy policy = new BackoffPolicy();
        Instant now = Instant.parse("2026-03-10T17:00:00Z");
        Map<Integer, BackoffState> perAccountState = new HashMap<>();

        // Ten failed attempts "from the same simulated IP", round-robin over five accounts: if any
        // cross-account or cross-IP aggregate limit existed, it would show up here as an ordinal
        // (and therefore a delay) that depends on the other nine attempts, not only on this
        // account's own prior failures. BackoffPolicy takes no IP concept at all (design.md,
        // decision 3: "identifier-agnostic por construcción"), so it never can.
        for (int attempt = 0; attempt < 10; attempt++) {
            int accountIndex = attempt % 5;
            BackoffState prior = perAccountState.getOrDefault(accountIndex, BackoffState.initial(now));
            int ordinal = policy.attemptOrdinal(prior, now);
            int expectedOrdinal = (attempt / 5) + 1;
            assertThat(ordinal)
                    .as("account %d's own ordinal must depend only on its own prior failures, "
                            + "never on the other nine attempts against the other four accounts",
                            accountIndex)
                    .isEqualTo(expectedOrdinal);
            assertThat(policy.delayFor(ordinal))
                    .as("with at most two attempts per account, no account ever crosses the "
                            + "third-failure delay threshold — there is no IP-level acceleration "
                            + "of it either")
                    .isEqualTo(Duration.ZERO);
            perAccountState.put(accountIndex, policy.afterFailure(ordinal, now));
        }
        assertThat(perAccountState).hasSize(5);

        Set<String> commandFieldNames = Arrays.stream(
                        AuthenticationCommand.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        assertThat(commandFieldNames)
                .as("the command's own field set must be real and non-empty")
                .isNotEmpty()
                .as("no IP-shaped field exists anywhere for a caller to even present")
                .doesNotContain("sourceIp", "ipAddress", "clientIp", "remoteAddress");
    }

    @Test
    void noProductionClassOfIdentityDependsOnAnyNetworkClientLibrary() {
        JavaClasses classes = assertNonEmptyIdentityProductionClasses();

        Set<String> forbiddenNetworkPackagePrefixes = Set.of("java.net.http.", "okhttp3.",
                "org.apache.hc.", "org.apache.http.");
        for (JavaClass javaClass : classes) {
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                String targetPackage = dependency.getTargetClass().getPackageName() + ".";
                boolean dependsOnNetworkClient = forbiddenNetworkPackagePrefixes.stream()
                        .anyMatch(targetPackage::startsWith);
                assertThat(dependsOnNetworkClient)
                        .as("%s must not depend on a network-client type (%s): no compromised-"
                                        + "password check against an external service exists in "
                                        + "this cut (brecha con destino: cambio 8 o posterior)",
                                javaClass.getFullName(), dependency.getTargetClass().getFullName())
                        .isFalse();
            }
        }
    }

    @Test
    void argon2ParametersAreTheDeclaredFloorNotACalibratedValue() {
        Argon2Profile floor = Argon2Profile.floor();

        assertThat(floor.memoryCostKib()).isEqualTo(Argon2Profile.FLOOR_MEMORY_COST_KIB);
        assertThat(floor.timeCost()).isEqualTo(Argon2Profile.FLOOR_TIME_COST);
        assertThat(floor.parallelism()).isEqualTo(Argon2Profile.FLOOR_PARALLELISM);
        assertThat(floor.hashLength()).isEqualTo(Argon2Profile.FLOOR_HASH_LENGTH_BYTES);
        assertThat(floor.saltLength()).isEqualTo(Argon2Profile.FLOOR_SALT_LENGTH_BYTES);

        Set<String> factoryMethodNames = Arrays.stream(Argon2Profile.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertThat(factoryMethodNames)
                .as("the declared factory methods must be real and non-empty")
                .isNotEmpty()
                .as("only the declared floor exists: no calibrated-against-real-hardware factory "
                        + "(docs/03-seguridad.md §4.1, brecha con destino: cambio 11)")
                .contains("floor")
                .doesNotContain("calibrated", "fromProductionMeasurement", "calibratedFor");
    }

    @Test
    void noPlaywrightTestExercisesTheAuthenticationFlowYet() throws IOException {
        Path appsDirectory = findAncestorContaining("apps").resolve("apps");
        List<Path> everyFileUnderApps;
        try (var walk = Files.walk(appsDirectory)) {
            everyFileUnderApps = walk.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().replace('\\', '/').contains("/target/"))
                    .toList();
        }
        assertThat(everyFileUnderApps)
                .as("the scanned tree must be real and populated, or the absence check below "
                        + "would pass vacuously")
                .isNotEmpty();

        List<Path> playwrightSpecs = everyFileUnderApps.stream()
                .filter(path -> path.toString().endsWith(".spec.ts")
                        || path.toString().endsWith(".spec.js"))
                .toList();
        assertThat(playwrightSpecs)
                .as("no Playwright end-to-end test of the login flow exists yet: no deployable "
                        + "React application exists yet either (specs/identity/spec.md, brecha con "
                        + "destino F1 o posterior)")
                .isEmpty();
    }

    @Test
    void noProductionClassOfIdentityDependsOnAnyLoggingFramework() {
        JavaClasses classes = assertNonEmptyIdentityProductionClasses();

        Set<String> forbiddenLoggingPackagePrefixes =
                Set.of("org.slf4j.", "java.util.logging.", "org.apache.commons.logging.");
        for (JavaClass javaClass : classes) {
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                String targetPackage = dependency.getTargetClass().getPackageName() + ".";
                boolean dependsOnLogging = forbiddenLoggingPackagePrefixes.stream()
                        .anyMatch(targetPackage::startsWith);
                assertThat(dependsOnLogging)
                        .as("%s must not depend on a logging framework (%s): this module's own "
                                        + "event record is the audit trail, never a log line "
                                        + "(design.md, decision 14)",
                                javaClass.getFullName(), dependency.getTargetClass().getFullName())
                        .isFalse();
            }
        }
    }

    /**
     * The absence half of the escenario publicado "Ninguna DEK retirada se recifra
     * automáticamente" (specs/identity/spec.md, requirement "Ausencia de ejecución real de la
     * rotación de la llave de datos (brecha con destino: cambio 9)"; column-encryption-and-mfa-totp
     * task 5.2). No job can re-encrypt anything yet because nothing can run a job: db-scheduler
     * (ADR-0016) is not even on the classpath, no production class depends on any scheduling API,
     * and the key-management surface offers no rotation operation to schedule. The behavioral half
     * — a retired key keeps decrypting — needs PostgreSQL, so it lives in {@code
     * ColumnEncryptionIT}, never in this container-free class. This test stops being true the day
     * change 9 delivers the rotation job, which is exactly when it should fail.
     */
    @Test
    void noScheduledJobReencryptsARetiredDataEncryptionKeyYet() {
        assertThatThrownBy(() -> Class.forName("com.github.kagkarlsson.scheduler.Scheduler"))
                .as("db-scheduler, the only permitted job runner (ADR-0016), is not on the "
                        + "classpath yet: it arrives with change 9")
                .isInstanceOf(ClassNotFoundException.class);

        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.confia");
        assertThat(classes.stream().map(JavaClass::getFullName).toList())
                .as("the scanned com.confia production class tree must be real and populated")
                .isNotEmpty();
        Set<String> forbiddenSchedulingPackagePrefixes =
                Set.of("com.github.kagkarlsson.", "org.springframework.scheduling.");
        for (JavaClass javaClass : classes) {
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                String targetPackage = dependency.getTargetClass().getPackageName() + ".";
                assertThat(forbiddenSchedulingPackagePrefixes.stream()
                        .anyMatch(targetPackage::startsWith))
                        .as("%s must not depend on a scheduling API (%s): no job of any kind "
                                        + "exists in this change",
                                javaClass.getFullName(), dependency.getTargetClass().getFullName())
                        .isFalse();
            }
        }

        Set<String> keyManagementMethodNames = Arrays.stream(
                        new Class<?>[] {ColumnEncryptionService.class,
                                DataEncryptionKeyRepository.class})
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .map(method -> method.getName().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        assertThat(keyManagementMethodNames)
                .as("the key-management surface must be real and non-empty")
                .contains("encryptfornewvalue", "decrypt", "findactiveorcreate", "findbyid")
                .as("and it offers no operation a rotation job could call")
                .noneMatch(name -> name.contains("reencrypt") || name.contains("rotate")
                        || name.contains("retire"));
    }

    /**
     * Proves the loop below is real (the same anti-vacuous-test discipline {@code
     * AuditScopeExclusionInventoryTest} and {@code MultiTenantSchemaIT} already follow).
     */
    private static JavaClasses assertNonEmptyIdentityProductionClasses() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.confia.identity");
        assertThat(classes.stream().map(JavaClass::getFullName).toList())
                .as("the scanned com.confia.identity production class tree must be real and "
                        + "populated, or every absence check over it would pass vacuously")
                .isNotEmpty();
        return classes;
    }

    /**
     * Walks up from wherever Surefire starts this module's working directory until it finds a
     * directory that directly contains {@code relativeMarker} — the same pattern {@code
     * SuppressionCitesAdrTest.findAncestorContaining} already establishes, copied here rather than
     * shared because that one is package-private to {@code com.confia.architecture}.
     */
    private static Path findAncestorContaining(String relativeMarker) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve(relativeMarker))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new UncheckedIOException(new IOException(
                "Could not find an ancestor containing " + relativeMarker + " starting from "
                        + Path.of("").toAbsolutePath()));
    }
}
