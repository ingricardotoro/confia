package com.confia.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.AccountSessionRevoker;
import com.confia.identity.application.AuthenticationCommand;
import com.confia.identity.application.PasswordResetIssuanceScheduler;
import com.confia.identity.application.PasswordResetLinkSender;
import com.confia.identity.application.ResetPasswordWithToken;
import com.confia.identity.application.StaffAccountRepository;
import com.confia.identity.domain.BackoffPolicy;
import com.confia.identity.domain.BackoffState;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.identity.testsupport.fixture.PasswordRecoveryScopeViolationFixtures;
import com.confia.shared.audit.AuditScopeExclusionInventoryTest;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.crypto.DataEncryptionKeyRepository;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethodCall;
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
import java.util.TreeSet;
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

    private static final String STAFF_ACCOUNT_TABLE =
            "confia.generated.jooq.tables.IdentityStaffAccount";
    private static final String STAFF_ACCOUNT_RECORD =
            "confia.generated.jooq.tables.records.IdentityStaffAccountRecord";

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
     * task 5.2). No job can re-encrypt anything yet because nothing registers one: db-scheduler
     * (ADR-0016) is on the classpath but no production class depends on any scheduling API,
     * and the key-management surface offers no rotation operation to schedule. The behavioral half
     * — a retired key keeps decrypting — needs PostgreSQL, so it lives in {@code
     * ColumnEncryptionIT}, never in this container-free class. This test stops being true the day
     * change 9 delivers the rotation job, which is exactly when it should fail.
     */
    @Test
    void noScheduledJobReencryptsARetiredDataEncryptionKeyYet() {
        // db-scheduler (ADR-0016) is on the class path since change 9 (the three processes share
        // one artifact), so its absence is no longer asserted. What stays true here is that no
        // com.confia production class depends on it; the rotation job itself is asserted over the
        // registered job definitions once they exist (background-jobs-with-db-scheduler task 2.1).
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
                        .anyMatch(targetPackage::startsWith)
                        && !AuditScopeExclusionInventoryTest.isTheConfinedUseOfDbScheduler(
                                javaClass.getPackageName(), targetPackage))
                        .as("%s must not depend on a scheduling API (%s): no job of any kind "
                                        + "exists in this change; db-scheduler is admitted only in the "
                                        + "jobs infrastructure package",
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

        assertThat(rotationJobsAmong(com.confia.shared.jobs.RegisteredJobs.ofTheWorker().stream()
                .map(com.confia.shared.jobs.JobDefinition::name).toList()))
                .as("data-encryption-key-batch-rotation: no registered job definition re-encrypts or rotates"
                        + " a data encryption key")
                .isEmpty();
    }

    /** The names that look like a data-encryption-key rotation job, among the registered definitions. */
    static java.util.List<String> rotationJobsAmong(java.util.List<String> jobNames) {
        return jobNames.stream().filter(name -> name.contains("rotat") || name.contains("reencrypt")
                || name.contains("re-encrypt") || name.contains("dek")).toList();
    }

    @Test
    void aRegisteredJobThatRotatesADataEncryptionKeyIsDetected() {
        assertThat(rotationJobsAmong(java.util.List.of("identity.purge-refresh-tokens",
                "identity.dek-rotation", "shared.reencrypt-columns")))
                .containsExactly("identity.dek-rotation", "shared.reencrypt-columns");
    }

    /**
     * Scenarios I25 and I27 (password-recovery-token design.md decision 11; specs/identity/spec.md,
     * "Ausencia de programación real" and "Ausencia de envío"): the issuance scheduler and the link
     * sender are ports without a production adapter. The db-scheduler adapter arrives with change
     * 9 and the email adapter with change 14; this test fails the day either lands, which is when
     * its owner rewrites it. It sees implementing classes, so a lambda in a configuration class is
     * outside its reach; the first adapter that matters will be a named class.
     */
    @Test
    void noProductionClassImplementsTheIssuanceSchedulerOrTheLinkSender() {
        JavaClasses production = assertNonEmptyProductionClasses();
        assertThat(production.contain(PasswordResetIssuanceScheduler.class)
                && production.contain(PasswordResetLinkSender.class))
                .as("both ports must be part of the scanned production tree, or the absence "
                        + "below would pass vacuously")
                .isTrue();

        assertThat(implementationsOf(production, PasswordResetIssuanceScheduler.class))
                .as("no production adapter of the issuance scheduling port exists yet "
                        + "(brecha con destino: cambio 9, background-jobs-with-db-scheduler)")
                .isEmpty();
        assertThat(implementationsOf(production, PasswordResetLinkSender.class))
                .as("no production adapter of the reset link delivery port exists yet "
                        + "(brecha con destino: cambio 14, transactional-email-adapter)")
                .isEmpty();
    }

    @Test
    void thePortAdapterRuleRejectsAFixtureThatImplementsEachPort() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                PasswordRecoveryScopeViolationFixtures.SchedulerAdapter.class,
                PasswordRecoveryScopeViolationFixtures.LinkSenderAdapter.class);

        assertThat(implementationsOf(fixtures, PasswordResetIssuanceScheduler.class))
                .containsExactly(PasswordRecoveryScopeViolationFixtures.SchedulerAdapter.class
                        .getName());
        assertThat(implementationsOf(fixtures, PasswordResetLinkSender.class))
                .containsExactly(PasswordRecoveryScopeViolationFixtures.LinkSenderAdapter.class
                        .getName());
    }

    /**
     * Scenario I33 (design.md decision 11, "El único camino para cambiar una contraseña es el
     * token"): across the whole production tree, the only class that calls {@code
     * StaffAccountRepository.replacePasswordHash} is {@code ResetPasswordWithToken}. A future
     * administrative reset would add a second caller and fail here, which is the point: it is a
     * different change with its own authorization story.
     */
    @Test
    void onlyResetPasswordWithTokenCallsReplacePasswordHash() {
        JavaClasses production = assertNonEmptyProductionClasses();

        assertThat(callersOfReplacePasswordHash(production))
                .as("exactly one production class rewrites a stored hash, and it is the reset "
                        + "with a token; a non-empty expectation, so a renamed method cannot "
                        + "make this pass vacuously")
                .containsExactly(ResetPasswordWithToken.class.getName());
    }

    @Test
    void theSinglePasswordWriterRuleRejectsAFixtureThatCallsReplacePasswordHash() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                PasswordRecoveryScopeViolationFixtures.SecondPasswordWriter.class);

        assertThat(callersOfReplacePasswordHash(fixtures))
                .containsExactly(PasswordRecoveryScopeViolationFixtures.SecondPasswordWriter.class
                        .getName());
    }

    /**
     * Hard condition H2, rule (a) (session-tokens-and-web-layer design.md decision 12; scenario
     * BI16). It replaces the former absence "no class of identity mentions a session or a refresh
     * token", which this change breaks on purpose: the password reset calls {@code
     * AccountSessionRevoker.revokeAllLive}. The rule looks for the real call, so a renamed method
     * cannot make it pass vacuously.
     */
    @Test
    void resetPasswordWithTokenRevokesEveryLiveSessionFamily() {
        JavaClasses production = assertNonEmptyProductionClasses();
        assertThat(production.contain(AccountSessionRevoker.class)
                && production.contain(ResetPasswordWithToken.class))
                .as("the port and the reset must be part of the scanned production tree")
                .isTrue();

        assertThat(callsRevokeAllLive(production.get(ResetPasswordWithToken.class)))
                .as("the password reset must call AccountSessionRevoker.revokeAllLive "
                        + "(hard condition H2)")
                .isTrue();
    }

    /**
     * H2, rule (b): no production class replaces a stored password hash without also calling the
     * revocation. Together with {@link #onlyResetPasswordWithTokenCallsReplacePasswordHash} it
     * means a future second path to change a password inherits the obligation to revoke.
     */
    @Test
    void noProductionClassReplacesAPasswordHashWithoutRevokingTheSessions() {
        JavaClasses production = assertNonEmptyProductionClasses();

        assertThat(callersOfReplacePasswordHash(production))
                .as("the rule must evaluate a real caller, not an empty set")
                .containsExactly(ResetPasswordWithToken.class.getName());
        assertThat(replacingTheHashWithoutRevoking(production)).isEmpty();
    }

    @Test
    void theRevocationRulesRejectTheFixturesThatSkipTheRevocation() {
        JavaClasses violating = new ClassFileImporter().importClasses(
                PasswordRecoveryScopeViolationFixtures.ResetWithoutRevocation.class,
                PasswordRecoveryScopeViolationFixtures.SecondPasswordWriter.class);
        JavaClasses compliant = new ClassFileImporter().importClasses(
                PasswordRecoveryScopeViolationFixtures.ResetWithRevocation.class);

        assertThat(callsRevokeAllLive(violating.get(
                PasswordRecoveryScopeViolationFixtures.ResetWithoutRevocation.class)))
                .as("rule (a) fails on a reset that never calls the revocation").isFalse();
        assertThat(replacingTheHashWithoutRevoking(violating))
                .as("rule (b) names each class that replaces the hash without revoking")
                .containsExactly(
                        PasswordRecoveryScopeViolationFixtures.ResetWithoutRevocation.class
                                .getName(),
                        PasswordRecoveryScopeViolationFixtures.SecondPasswordWriter.class
                                .getName());
        assertThat(callsRevokeAllLive(compliant.get(
                PasswordRecoveryScopeViolationFixtures.ResetWithRevocation.class)))
                .as("the control that revoking satisfies rule (a)").isTrue();
        assertThat(replacingTheHashWithoutRevoking(compliant))
                .as("the control that replacing the hash and revoking satisfies rule (b)")
                .isEmpty();
    }

    /**
     * Review of PR 9, I-1: the rule on callers of {@code replacePasswordHash} would not see a class
     * that sets the column itself with jOOQ. Across the whole production tree, the only code that
     * touches the {@code password_hash} column of the generated table, or the setter of its record,
     * is {@code JooqStaffAccountRepository.replacePasswordHash}. Readers use the getter of the
     * record, which this rule does not forbid.
     */
    @Test
    void onlyReplacePasswordHashOfTheRepositoryWritesThePasswordHashColumn() {
        JavaClasses production = assertNonEmptyProductionClasses();

        assertThat(writersOfThePasswordHashColumn(production))
                .as("exactly one code unit writes the column, and it is the repository method; "
                        + "a non-empty expectation, so a renamed column cannot pass vacuously")
                .containsExactly(JooqStaffAccountRepository.class.getName()
                        + ".replacePasswordHash");
    }

    @Test
    void thePasswordHashColumnRuleRejectsAFixtureThatSetsTheColumnItself() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                PasswordRecoveryScopeViolationFixtures.PasswordHashColumnWriter.class);

        assertThat(writersOfThePasswordHashColumn(fixtures))
                .containsExactly(PasswordRecoveryScopeViolationFixtures.PasswordHashColumnWriter
                        .class.getName() + ".overwrite");
    }

    private static List<String> writersOfThePasswordHashColumn(JavaClasses classes) {
        Set<String> writers = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            for (JavaFieldAccess access : javaClass.getFieldAccessesFromSelf()) {
                if (access.getTarget().getOwner().getFullName().equals(STAFF_ACCOUNT_TABLE)
                        && access.getTarget().getName().equals("PASSWORD_HASH")) {
                    writers.add(access.getOrigin().getFullName().replaceAll("\\(.*\\)", ""));
                }
            }
            for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                if (call.getTarget().getOwner().getFullName().equals(STAFF_ACCOUNT_RECORD)
                        && call.getTarget().getName().equals("setPasswordHash")) {
                    writers.add(call.getOrigin().getFullName().replaceAll("\\(.*\\)", ""));
                }
            }
        }
        return List.copyOf(writers);
    }

    private static List<String> implementationsOf(JavaClasses classes, Class<?> port) {
        return classes.stream()
                .filter(javaClass -> !javaClass.isInterface() && javaClass.isAssignableTo(port))
                .map(JavaClass::getFullName)
                .sorted()
                .toList();
    }

    private static List<String> callersOfReplacePasswordHash(JavaClasses classes) {
        return classes.stream()
                .filter(javaClass -> javaClass.getMethodCallsFromSelf().stream()
                        .anyMatch(call -> call.getTarget().getName().equals("replacePasswordHash")
                                && call.getTarget().getOwner()
                                        .isAssignableTo(StaffAccountRepository.class)))
                .map(JavaClass::getFullName)
                .sorted()
                .toList();
    }

    private static boolean callsRevokeAllLive(JavaClass javaClass) {
        return javaClass.getMethodCallsFromSelf().stream()
                .anyMatch(call -> call.getTarget().getName().equals("revokeAllLive")
                        && call.getTarget().getOwner().isAssignableTo(AccountSessionRevoker.class));
    }

    /** Classes that call {@code replacePasswordHash} and never {@code revokeAllLive}. */
    private static List<String> replacingTheHashWithoutRevoking(JavaClasses classes) {
        return classes.stream()
                .filter(javaClass -> callersOfReplacePasswordHash(classes)
                        .contains(javaClass.getFullName()))
                .filter(javaClass -> !callsRevokeAllLive(javaClass))
                .map(JavaClass::getFullName)
                .sorted()
                .toList();
    }

    private static JavaClasses assertNonEmptyProductionClasses() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.confia");
        assertThat(classes.stream().map(JavaClass::getFullName).toList())
                .as("the scanned com.confia production class tree must be real and populated")
                .isNotEmpty();
        return classes;
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
