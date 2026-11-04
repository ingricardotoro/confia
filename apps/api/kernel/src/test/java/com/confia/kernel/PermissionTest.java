package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The closed catalog of permissions (scenarios AZ04, AZ05 and AZ06; design.md, decision D2): 89
 * entries, 88 of them a module and one of the six verbs of {@code docs/03} section 5.1 and one the
 * request to void an invoice. The catalog is code and holds no role; which role grants what is
 * {@code StaffRole}'s matrix.
 */
class PermissionTest {

    private static final Pattern CODE_SHAPE = Pattern.compile("^[a-z]+:[a-z]+(-[a-z]+)*$");

    private static final Set<String> MODULES = Set.of("identity", "organization", "students", "guardians",
            "catalog", "scholarships", "charges", "ledger", "payments", "cashbox", "invoicing", "collections",
            "reconciliation", "documents", "reporting", "notifications", "audit", "settings");

    private static final Set<String> VERBS = Set.of("create", "read", "update", "void", "approve", "export");

    @Test
    void hasExactlyEightyNineDistinctCodes() {
        List<String> codes = Arrays.stream(Permission.values()).map(Permission::code).toList();

        assertThat(codes).hasSize(89).doesNotHaveDuplicates();
    }

    @Test
    void everyCodeHasTheModuleVerbShape() {
        for (Permission permission : Permission.values()) {
            assertThat(permission.code()).as(permission.name()).matches(CODE_SHAPE);
        }
    }

    @Test
    void eightyEightCodesPairAListedModuleWithOneOfTheSixVerbs() {
        List<Permission> moduleAndVerb = Arrays.stream(Permission.values())
                .filter(permission -> VERBS.contains(permission.verb()))
                .toList();

        assertThat(moduleAndVerb).hasSize(88);
        assertThat(moduleAndVerb).allSatisfy(permission -> assertThat(MODULES).contains(permission.module()));
    }

    @Test
    void theEighteenModulesAreExactlyTheListedOnes() {
        Set<String> modules = Arrays.stream(Permission.values())
                .map(Permission::module)
                .collect(Collectors.toSet());

        assertThat(modules).isEqualTo(MODULES);
    }

    @Test
    void theOnlyCodeOutsideTheSixVerbsIsTheRequestToVoidAnInvoice() {
        List<Permission> others = Arrays.stream(Permission.values())
                .filter(permission -> !VERBS.contains(permission.verb()))
                .toList();

        assertThat(others).containsExactly(Permission.INVOICING_REQUEST_VOID);
        assertThat(Permission.INVOICING_REQUEST_VOID.code()).isEqualTo("invoicing:request-void");
        assertThat(Permission.INVOICING_REQUEST_VOID.module()).isEqualTo("invoicing");
        assertThat(Permission.INVOICING_REQUEST_VOID.verb()).isEqualTo("request-void");
    }

    @Test
    void theRequestToVoidIsADifferentEntryFromVoid() {
        assertThat(Permission.INVOICING_REQUEST_VOID).isNotEqualTo(Permission.INVOICING_VOID);
        assertThat(Permission.INVOICING_VOID.code()).isEqualTo("invoicing:void");
    }

    @Test
    void permissionsNoRoleHasDoNotExist() {
        assertThat(Permission.ofCode("ledger:update")).isEmpty();
        assertThat(Permission.ofCode("payments:update")).isEmpty();
        assertThat(Permission.ofCode("invoicing:update")).isEmpty();
        assertThat(Arrays.stream(Permission.values()).map(Permission::code))
                .doesNotContain("ledger:update", "payments:update", "invoicing:update");
    }

    @Test
    void ofCodeResolvesEveryCodeToItsOwnConstant() {
        for (Permission permission : Permission.values()) {
            assertThat(Permission.ofCode(permission.code())).as(permission.code()).contains(permission);
        }
    }

    @Test
    void ofCodeAnswersEmptyForAnUnknownCode() {
        assertThat(Permission.ofCode("payments:delete")).isEqualTo(Optional.empty());
        assertThat(Permission.ofCode("PAYMENTS:CREATE")).isEmpty();
        assertThat(Permission.ofCode("")).isEmpty();
        assertThat(Permission.ofCode(null)).isEmpty();
    }

    @Test
    void moduleAndVerbSplitTheCodeAtTheColon() {
        assertThat(Permission.PAYMENTS_CREATE.module()).isEqualTo("payments");
        assertThat(Permission.PAYMENTS_CREATE.verb()).isEqualTo("create");
        assertThat(Permission.AUDIT_EXPORT.module()).isEqualTo("audit");
        assertThat(Permission.AUDIT_EXPORT.verb()).isEqualTo("export");
    }
}
