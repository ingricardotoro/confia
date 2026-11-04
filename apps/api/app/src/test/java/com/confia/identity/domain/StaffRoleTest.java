package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.Permission;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The closed catalog of staff roles and the matrix of what each grants (scenarios AZ01 to AZ03 and
 * AZ05 to AZ13; design.md, decisions D2 and D3). {@code PermissionMatrixParityTest} compares the same
 * matrix with {@code docs/03} cell by cell; this class pins the scenarios that name one column.
 */
class StaffRoleTest {

    private static final Set<String> ALL_MODULES = Arrays.stream(Permission.values())
            .map(Permission::module)
            .collect(Collectors.toSet());

    @Test
    void theCatalogHasExactlyTheSixRolesInCatalogOrder() {
        assertThat(Arrays.stream(StaffRole.values()).map(StaffRole::code))
                .containsExactly("super_admin", "administrator", "cashier", "accounting", "auditor",
                        "academic_coordinator");
    }

    @Test
    void noRoleBelongsToAGuardianThePortalOrThePlatform() {
        assertThat(Arrays.stream(StaffRole.values()).map(StaffRole::code))
                .noneMatch(code -> code.contains("guardian") || code.contains("portal") || code.contains("platform"));
    }

    @Test
    void noMigrationCreatesAGlobalTableOfRolesOrPermissions() throws IOException {
        Path migrations = Paths.get("src/main/resources/db/migration").toAbsolutePath();
        Pattern createTable = Pattern.compile("(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_0-9.]+)");
        Pattern globalCatalogTable = Pattern.compile("(?i)^(?:identity_|shared_)?(roles?|permissions?|role_permissions?)$");

        List<String> tables;
        try (Stream<Path> files = Files.list(migrations)) {
            tables = files.filter(file -> file.getFileName().toString().endsWith(".sql"))
                    .flatMap(file -> {
                        Matcher matcher = createTable.matcher(read(file));
                        return matcher.results().map(result -> result.group(1));
                    })
                    .toList();
        }

        assertThat(tables).as("the migrations create tables, so the search is not empty").contains(
                "organization_institution", "identity_staff_account");
        assertThat(tables).noneMatch(table -> globalCatalogTable.matcher(table).matches());
    }

    @Test
    void ofCodeResolvesEachRoleFromItsCode() {
        for (StaffRole role : StaffRole.values()) {
            assertThat(StaffRole.ofCode(role.code())).as(role.code()).contains(role);
        }
    }

    @Test
    void anUnknownCodeIsNeitherARoleNorADefault() {
        assertThat(StaffRole.ofCode("guardian")).isEqualTo(Optional.empty());
        assertThat(StaffRole.ofCode("SUPER_ADMIN")).isEmpty();
        assertThat(StaffRole.ofCode("")).isEmpty();
        assertThat(StaffRole.ofCode(null)).isEmpty();
    }

    @Test
    void thePermissionsOfARoleCannotBeChangedByItsCaller() {
        Set<Permission> permissions = StaffRole.CASHIER.permissions();

        assertThatThrownBy(() -> permissions.add(Permission.PAYMENTS_VOID))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(StaffRole.CASHIER.permissions()).doesNotContain(Permission.PAYMENTS_VOID);
    }

    @Test
    void everyRoleGrantsTheCountOfPermissionsOfItsColumn() {
        assertThat(sizes()).containsExactly(88, 61, 18, 31, 35, 23);
    }

    @Test
    void theSuperAdminHasTheEightyEightModuleAndVerbPermissionsAndNotTheRequest() {
        Set<Permission> expected = EnumSet.allOf(Permission.class);
        expected.remove(Permission.INVOICING_REQUEST_VOID);

        assertThat(StaffRole.SUPER_ADMIN.permissions()).hasSize(88).isEqualTo(expected);
    }

    @Test
    void theCashierRequestsTheVoidButDoesNotVoid() {
        Set<Permission> invoicing = permissionsOf(StaffRole.CASHIER, "invoicing");

        assertThat(invoicing).containsExactlyInAnyOrder(Permission.INVOICING_CREATE, Permission.INVOICING_READ,
                Permission.INVOICING_REQUEST_VOID);
        assertThat(rolesGranting(Permission.INVOICING_VOID))
                .containsExactlyInAnyOrder(StaffRole.SUPER_ADMIN, StaffRole.ACCOUNTING);
        assertThat(rolesGranting(Permission.INVOICING_REQUEST_VOID)).containsExactly(StaffRole.CASHIER);
    }

    @Test
    void theCashierCollectsAndOpensTheCashboxButOtherwiseOnlyReads() {
        Set<Permission> writes = StaffRole.CASHIER.permissions().stream()
                .filter(permission -> !permission.verb().equals("read"))
                .collect(Collectors.toSet());

        assertThat(writes).containsExactlyInAnyOrder(Permission.PAYMENTS_CREATE, Permission.CASHBOX_CREATE,
                Permission.INVOICING_CREATE, Permission.INVOICING_REQUEST_VOID);
    }

    @Test
    void theAdministratorDoesNotCollectAndDoesNotReadTheAuditLog() {
        assertThat(permissionsOf(StaffRole.ADMINISTRATOR, "payments")).containsExactlyInAnyOrder(
                Permission.PAYMENTS_READ, Permission.PAYMENTS_VOID, Permission.PAYMENTS_APPROVE,
                Permission.PAYMENTS_EXPORT);
        assertThat(StaffRole.ADMINISTRATOR.permissions()).doesNotContain(Permission.PAYMENTS_CREATE,
                Permission.CASHBOX_CREATE);
        assertThat(permissionsOf(StaffRole.ADMINISTRATOR, "audit")).isEmpty();
    }

    @Test
    void theAuditorOnlyReadsAndExportsAndReadsEveryModule() {
        Set<Permission> auditor = StaffRole.AUDITOR.permissions();

        assertThat(auditor).allSatisfy(permission -> assertThat(permission.verb()).isIn("read", "export"));
        assertThat(auditor.stream().filter(permission -> permission.verb().equals("read")).count()).isEqualTo(18);
        assertThat(auditor.stream().filter(permission -> permission.verb().equals("read"))
                .map(Permission::module).collect(Collectors.toSet())).isEqualTo(ALL_MODULES);
        assertThat(auditor.stream().filter(permission -> permission.verb().equals("export"))
                .map(Permission::module).collect(Collectors.toSet()))
                .hasSize(17)
                .doesNotContain("identity");
    }

    @Test
    void onlyTheSuperAdminAndTheAuditorReadAndExportTheAuditLog() {
        assertThat(rolesGranting(Permission.AUDIT_READ))
                .containsExactlyInAnyOrder(StaffRole.SUPER_ADMIN, StaffRole.AUDITOR);
        assertThat(rolesGranting(Permission.AUDIT_EXPORT))
                .containsExactlyInAnyOrder(StaffRole.SUPER_ADMIN, StaffRole.AUDITOR);
    }

    @Test
    void threeRolesReadIdentityAndNoPermissionIsTheOwnMfa() {
        assertThat(rolesGranting(Permission.IDENTITY_READ))
                .containsExactlyInAnyOrder(StaffRole.SUPER_ADMIN, StaffRole.ADMINISTRATOR, StaffRole.AUDITOR);
        assertThat(Arrays.stream(Permission.values()).map(Permission::code))
                .noneMatch(code -> code.contains("mfa") || code.contains("own"));
    }

    @Test
    void aScopeInsideAPermissionIsNotAPermission() {
        assertThat(Arrays.stream(Permission.values()).filter(permission -> permission.module().equals("reporting"))
                .map(Permission::code)).containsExactlyInAnyOrder("reporting:read", "reporting:export");
        assertThat(StaffRole.CASHIER.permissions()).contains(Permission.REPORTING_READ)
                .doesNotContain(Permission.REPORTING_EXPORT);
        assertThat(StaffRole.ACADEMIC_COORDINATOR.permissions()).contains(Permission.REPORTING_READ,
                Permission.REPORTING_EXPORT);
    }

    @Test
    void everyPermissionOfTheCatalogIsGrantedBySomeRole() {
        for (Permission permission : Permission.values()) {
            assertThat(rolesGranting(permission)).as(permission.code()).isNotEmpty();
        }
    }

    @Test
    void theAccountingRoleVoidsInvoicesAndManagesReconciliation() {
        assertThat(permissionsOf(StaffRole.ACCOUNTING, "invoicing")).containsExactlyInAnyOrder(
                Permission.INVOICING_CREATE, Permission.INVOICING_READ, Permission.INVOICING_VOID,
                Permission.INVOICING_APPROVE, Permission.INVOICING_EXPORT);
        assertThat(permissionsOf(StaffRole.ACCOUNTING, "reconciliation")).hasSize(5);
        assertThat(permissionsOf(StaffRole.ACCOUNTING, "documents")).isEmpty();
    }

    @Test
    void theAcademicCoordinatorManagesStudentsAndDocumentsButHasNoFinance() {
        assertThat(permissionsOf(StaffRole.ACADEMIC_COORDINATOR, "documents")).hasSize(5);
        assertThat(permissionsOf(StaffRole.ACADEMIC_COORDINATOR, "students")).containsExactlyInAnyOrder(
                Permission.STUDENTS_CREATE, Permission.STUDENTS_READ, Permission.STUDENTS_UPDATE,
                Permission.STUDENTS_EXPORT);
        for (String finance : List.of("ledger", "payments", "cashbox", "invoicing", "reconciliation", "audit",
                "settings", "identity")) {
            assertThat(permissionsOf(StaffRole.ACADEMIC_COORDINATOR, finance)).as(finance).isEmpty();
        }
    }

    @Test
    void nobodyUpdatesTheLedgerAndNobodyDeletes() {
        for (StaffRole role : StaffRole.values()) {
            assertThat(role.permissions().stream().map(Permission::code))
                    .as(role.code())
                    .doesNotContain("ledger:update", "payments:update", "invoicing:update")
                    .noneMatch(code -> code.endsWith(":delete"));
        }
    }

    private static List<Integer> sizes() {
        return Arrays.stream(StaffRole.values()).map(role -> role.permissions().size()).toList();
    }

    private static Set<Permission> permissionsOf(StaffRole role, String module) {
        return role.permissions().stream()
                .filter(permission -> permission.module().equals(module))
                .collect(Collectors.toSet());
    }

    private static Set<StaffRole> rolesGranting(Permission permission) {
        return Arrays.stream(StaffRole.values())
                .filter(role -> role.permissions().contains(permission))
                .collect(Collectors.toSet());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file).toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
