package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.architecture.authorizationscope.AuthorizationScopeViolationFixtures;
import com.confia.shared.jobs.JobDefinition;
import com.confia.shared.jobs.RegisteredJobs;
import com.confia.shared.web.edge.PortalSecurityConfiguration;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * What the authorization change deliberately does not build, each absence with its owner
 * (rbac-permission-matrix-and-audit-integration, specs/authorization; scenarios AZ74 to AZ77). They stop
 * being true the day the owner delivers them, which is when they should fail:
 *
 * <ul>
 *   <li>AZ74 (change 8b): no route manages roles or accounts, reads the audit trail, enrolls a second
 *       factor with a full session or resets another account's password;
 *   <li>AZ75 (change 8b): no use case, migration or task inserts role assignments, and no migration
 *       inserts accounts;
 *   <li>AZ76 (phase 8): the portal chain holds no roles, permissions or list of routes with a permission;
 *   <li>AZ77 (the changes that own each piece): no void request or approval, no restriction of scope
 *       inside a permission, no MFA integrity job, no segregation report and no Redis client for the cap.
 * </ul>
 *
 * Each detector iterates a base set proven non-empty first, so an absence cannot pass over nothing, and
 * is proven on the fixtures of {@link AuthorizationScopeViolationFixtures}, the negative control. No
 * Docker: the class tree, the migration text and the real worker context with its executor off.
 */
class AuthorizationScopeExclusionInventoryTest {

    private static final Pattern VOID_FLOW = Pattern.compile(
            "(?i).*(Void)(Request|Approv).*|.*(Request|Approv)\\w*Void.*");
    private static final Pattern PERMISSION_SCOPE = Pattern.compile(
            "(?i).*(ScopedPermission|PermissionScope|ScopeRestriction).*");
    private static final Pattern MFA_INTEGRITY = Pattern.compile(
            "(?i).*(Mfa\\w*Integrity|Integrity\\w*Mfa).*");
    private static final Pattern SEGREGATION_REPORT = Pattern.compile("(?i).*Segregation.*");
    private static final Pattern REDIS = Pattern.compile("(?i).*Redis.*");
    private static final Pattern ROLE_OR_ASSIGNMENT_JOB = Pattern.compile("(?i).*(role|assign|super-?admin).*");

    // --- AZ74: no route of management ---

    @Test
    void noRouteManagesRolesOrAccountsReadsTheTrailOrEnrollsAFullSessionSecondFactor() {
        List<String> routes = RouteInventory.routesOf(productionClasses());

        assertThat(routes).as("the scan sees the real routes, so the absence is not vacuous")
                .contains(AuthorizationAbsences.ROLES_REPORT, "POST /api/v1/auth/sessions",
                        "POST /api/v1/auth/mfa/enrollments", "POST /api/v1/auth/mfa/verifications");
        assertThat(AuthorizationAbsences.forbiddenRoutes(routes))
                .as("change 8b delivers the management of roles and accounts, the reading of the "
                        + "trail and the voluntary enrollment of a second factor")
                .isEmpty();
    }

    @Test
    void theRouteDetectorRejectsEachFixtureAndAcceptsTheReportAndTheRestrictedRoutes() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                AuthorizationScopeViolationFixtures.RoleAssignmentController.class,
                AuthorizationScopeViolationFixtures.AccountDeactivationController.class,
                AuthorizationScopeViolationFixtures.AuditLogController.class,
                AuthorizationScopeViolationFixtures.FullSessionMfaEnrollmentController.class,
                AuthorizationScopeViolationFixtures.AdminPasswordResetController.class);

        List<String> found = AuthorizationAbsences.forbiddenRoutes(RouteInventory.routesOf(fixtures));

        assertThat(found).anySatisfy(route -> assertThat(route).contains("manages roles or accounts",
                "PUT /api/v1/identity/accounts/{id}/role"));
        assertThat(found).anySatisfy(route -> assertThat(route).contains("manages roles or accounts",
                "DELETE /api/v1/identity/accounts/{id}"));
        assertThat(found).anySatisfy(route -> assertThat(route).contains("reads the audit trail",
                "GET /api/v1/audit/entries"));
        assertThat(found).anySatisfy(route -> assertThat(route).contains("second factor with a full "
                + "session", "POST /api/v1/auth/mfa/totp-secrets"));
        assertThat(found).anySatisfy(route -> assertThat(route).contains("resets a password",
                "POST /api/v1/identity/accounts/{id}/password-reset"));
        assertThat(AuthorizationAbsences.forbiddenRoutes(List.of(AuthorizationAbsences.ROLES_REPORT,
                AuthorizationAbsences.RESTRICTED_MFA_ROUTES.get(0),
                AuthorizationAbsences.RESTRICTED_MFA_ROUTES.get(1), "POST /api/v1/auth/sessions")))
                .as("the control: the report and the restricted routes are not violations").isEmpty();
    }

    // --- AZ75: roles are assigned only by tests ---

    @Test
    void noUseCaseMigrationOrTaskInsertsRoleAssignmentsAndNoMigrationInsertsAccounts() {
        JavaClasses production = productionClasses();

        assertThat(AuthorizationAbsences.roleAssignmentUsers(production))
                .as("the scan sees the one reader of the assignment table, so the absence of "
                        + "writers is not vacuous")
                .containsExactly("com.confia.identity.infrastructure.JooqStaffRoleAssignmentRepository");
        assertThat(AuthorizationAbsences.roleAssignmentWriters(production)).isEmpty();
        List<String> useCases = AuthorizationAbsences.simpleNamesIn(production,
                "com.confia.identity.application");
        assertThat(useCases).contains("LogIn", "StaffRoleAssignmentRepository");
        assertThat(AuthorizationAbsences.roleManagementNames(useCases)).isEmpty();

        Map<String, String> migrations = AuthorizationAbsences.migrationSqlByFile();
        assertThat(migrations).as("the migrations are the real ones, V14 among them")
                .containsKey("V14__create_identity_staff_account_role.sql");
        assertThat(AuthorizationAbsences.migrationsThatInsertAccountsOrAssignments(migrations))
                .isEmpty();

        List<String> jobs = RegisteredJobs.ofTheWorker().stream().map(JobDefinition::name).toList();
        assertThat(jobs).as("the registry of the worker is real and non-empty").isNotEmpty();
        assertThat(jobs.stream().filter(name -> ROLE_OR_ASSIGNMENT_JOB.matcher(name).matches())
                .toList()).isEmpty();
    }

    @Test
    void theAssignmentDetectorsRejectTheFixturesThatInsertAndNameTheUseCases() {
        JavaClasses writer = new ClassFileImporter().importClasses(
                AuthorizationScopeViolationFixtures.AssignmentWriter.class);
        JavaClasses reader = new ClassFileImporter().importClasses(
                AuthorizationScopeViolationFixtures.AssignmentReader.class);

        assertThat(AuthorizationAbsences.roleAssignmentWriters(writer)).containsExactly(
                AuthorizationScopeViolationFixtures.AssignmentWriter.class.getName());
        assertThat(AuthorizationAbsences.roleAssignmentWriters(reader))
                .as("the control: a class that only reads the table is not a writer").isEmpty();
        assertThat(AuthorizationAbsences.roleAssignmentUsers(reader)).hasSize(1);
        assertThat(AuthorizationAbsences.roleManagementNames(List.of("AssignStaffRole",
                "ChangeStaffRole", "CreateFirstSuperAdmin", "LogIn", "StaffRoleAssignmentRepository",
                "ListRolePermissions"))).containsExactly("AssignStaffRole", "ChangeStaffRole",
                "CreateFirstSuperAdmin");
        assertThat(AuthorizationAbsences.migrationsThatInsertAccountsOrAssignments(Map.of(
                "V99__seed_role.sql", "INSERT INTO identity_staff_account_role (a) VALUES (1);",
                "V98__seed_account.sql", "insert into public.identity_staff_account (a) values (1);",
                "V97__other.sql", "insert into shared_audit_chain_head (a) values (1);")))
                .containsExactly("V98__seed_account.sql", "V99__seed_role.sql");
        assertThat(ROLE_OR_ASSIGNMENT_JOB.matcher("identity.assign-role").matches()).isTrue();
        assertThat(ROLE_OR_ASSIGNMENT_JOB.matcher("identity.purge-refresh-tokens").matches()).isFalse();
    }

    // --- AZ76: the portal has no access control by roles ---

    @Test
    void thePortalChainHoldsNoRolePermissionOrListOfRoutesWithAPermission() {
        JavaClasses production = productionClasses();
        JavaClass portal = production.get(PortalSecurityConfiguration.class);

        assertThat(portal.getDirectDependenciesFromSelf().stream()
                .map(dependency -> dependency.getTargetClass().getSimpleName()).toList())
                .as("the scan sees the real chain: it is built from the public list and the shared "
                        + "deny-by-default builder")
                .contains("SecurityChains", "PublicEndpoints");
        assertThat(authorizationDependenciesOf(portal)).isEmpty();
    }

    @Test
    void thePortalDetectorRejectsAChainThatCarriesTheListOfRoutesWithAPermission() {
        JavaClasses fixture = new ClassFileImporter().importClasses(
                AuthorizationScopeViolationFixtures.PortalChainWithPermissions.class);

        assertThat(authorizationDependenciesOf(fixture.get(
                AuthorizationScopeViolationFixtures.PortalChainWithPermissions.class)))
                .contains("com.confia.shared.web.authorization.PermissionedEndpoints");
    }

    // --- AZ77: none of the pieces that belong to later changes exists ---

    @Test
    void noVoidFlowNoScopeInsideAPermissionNoMfaIntegrityJobNoSegregationReportAndNoRedisCap() {
        JavaClasses production = productionClasses();
        List<String> names = production.stream().map(JavaClass::getSimpleName).toList();

        assertThat(names).as("the scan sees the real classes, so the absences are not vacuous")
                .contains("PermissionDecider", "DenialWindows", "ListRolePermissions");
        assertThat(matching(names, VOID_FLOW)).as("void request or approval").isEmpty();
        assertThat(matching(names, PERMISSION_SCOPE)).as("scope inside a permission").isEmpty();
        assertThat(matching(names, MFA_INTEGRITY)).as("MFA integrity job").isEmpty();
        assertThat(matching(names, SEGREGATION_REPORT)).as("segregation report").isEmpty();
        assertThat(matching(names, REDIS)).as("a Redis client for the cap").isEmpty();
        List<String> jobs = RegisteredJobs.ofTheWorker().stream().map(JobDefinition::name).toList();
        assertThat(jobs).isNotEmpty();
        assertThat(matching(jobs, Pattern.compile("(?i).*mfa.*integrity.*|.*integrity.*mfa.*")))
                .as("no registered job checks the MFA integrity").isEmpty();
        assertThat(redisDependenciesOfTheAuthorization(production)).isEmpty();
    }

    @Test
    void theNameDetectorsRejectTheFixturesOfEachPiece() {
        assertThat(matching(List.of(
                AuthorizationScopeViolationFixtures.RequestInvoiceVoid.class.getSimpleName(),
                AuthorizationScopeViolationFixtures.ApproveInvoiceVoid.class.getSimpleName(),
                "ListRolePermissions"), VOID_FLOW)).containsExactlyInAnyOrder("RequestInvoiceVoid",
                "ApproveInvoiceVoid");
        assertThat(matching(List.of(
                AuthorizationScopeViolationFixtures.ScopedPermissionCheck.class.getSimpleName(),
                "PermissionDecider"), PERMISSION_SCOPE)).containsExactly("ScopedPermissionCheck");
        assertThat(matching(List.of("MfaIntegrityJob", "MfaPolicy"), MFA_INTEGRITY))
                .containsExactly("MfaIntegrityJob");
        assertThat(matching(List.of(
                AuthorizationScopeViolationFixtures.SegregationOfDutiesReport.class.getSimpleName(),
                "DenialWindows"), SEGREGATION_REPORT)).containsExactly("SegregationOfDutiesReport");
        assertThat(matching(List.of(
                AuthorizationScopeViolationFixtures.RedisDenialCounter.class.getSimpleName(),
                "InMemoryRateLimiter"), REDIS)).containsExactly("RedisDenialCounter");
    }

    // ---------------------------------------------------------------------------------------------

    private static List<String> matching(List<String> names, Pattern pattern) {
        return names.stream().filter(name -> pattern.matcher(name).matches()).sorted().toList();
    }

    /** The types of the authorization of the administrative chain that {@code type} depends on directly. */
    private static List<String> authorizationDependenciesOf(JavaClass type) {
        return type.getDirectDependenciesFromSelf().stream()
                .map(Dependency::getTargetClass).map(JavaClass::getFullName)
                .filter(name -> name.startsWith("com.confia.shared.web.authorization.")
                        || name.equals("com.confia.kernel.Permission")
                        || name.equals("com.confia.identity.domain.StaffRole")
                        || name.startsWith("org.springframework.security.access.prepost."))
                .distinct().sorted().toList();
    }

    private static List<String> redisDependenciesOfTheAuthorization(JavaClasses production) {
        return production.stream()
                .filter(type -> type.getPackageName().startsWith("com.confia.shared.web.authorization"))
                .flatMap(type -> type.getDirectDependenciesFromSelf().stream())
                .map(dependency -> dependency.getTargetClass().getFullName())
                .filter(name -> name.contains("redis") || name.contains("lettuce")
                        || name.contains("jedis"))
                .distinct().sorted().toList();
    }
}
