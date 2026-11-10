package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.StaffRole;
import com.confia.kernel.Permission;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ListRolePermissions} (rbac-permission-matrix-and-audit-integration design.md D16; scenarios
 * I294 and I296): the six roles in the order of the catalog and the permissions of each sorted by
 * code, from the constant matrix of {@link StaffRole} alone. The use case takes no argument, so there
 * is no transaction, no store and no actor to double.
 */
class ListRolePermissionsTest {

    private final ListRolePermissions list = new ListRolePermissions();

    @Test
    void itAnswersTheSixRolesInTheOrderOfTheCatalog() {
        List<RolePermissionsView> views = list.execute();

        assertThat(views).extracting(RolePermissionsView::code).containsExactly(
                "super_admin", "administrator", "cashier", "accounting", "auditor",
                "academic_coordinator");
        assertThat(views).extracting(RolePermissionsView::code).containsExactlyElementsOf(
                Arrays.stream(StaffRole.values()).map(StaffRole::code).toList());
    }

    @Test
    void thePermissionsOfEachRoleAreSortedByCodeAndAreExactlyTheMatrix() {
        for (RolePermissionsView view : list.execute()) {
            StaffRole role = StaffRole.ofCode(view.code()).orElseThrow();
            List<String> expected = role.permissions().stream().map(Permission::code).sorted().toList();

            assertThat(view.permissions()).as("permissions of %s", view.code())
                    .containsExactlyElementsOf(expected).isSorted().doesNotHaveDuplicates();
        }
    }

    @Test
    void theCashierHoldsTheRequestToVoidAndNotTheVoidItself() {
        RolePermissionsView cashier = viewOf("cashier");

        assertThat(cashier.permissions()).contains("invoicing:request-void")
                .doesNotContain("invoicing:void");
    }

    @Test
    void onlyTheRolesThatReadIdentityHoldIdentityRead() {
        assertThat(list.execute().stream()
                .filter(view -> view.permissions().contains("identity:read"))
                .map(RolePermissionsView::code))
                .containsExactly("super_admin", "administrator", "auditor");
    }

    @Test
    void twoCallsAnswerEqualViewsInTheSameOrder() {
        assertThat(list.execute()).isEqualTo(list.execute());
    }

    @Test
    void theAnswerCannotBeModifiedByTheCaller() {
        List<RolePermissionsView> views = list.execute();

        assertThatThrownBy(views::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> views.get(0).permissions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aViewKeepsNothingOfTheListItWasBuiltFromAndRejectsNulls() {
        List<String> source = new java.util.ArrayList<>(List.of("a:read"));
        RolePermissionsView view = new RolePermissionsView("role", source);
        source.add("b:read");

        assertThat(view.permissions()).containsExactly("a:read");
        assertThatThrownBy(() -> new RolePermissionsView(null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RolePermissionsView("role", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theOrderIsTheNaturalOrderOfTheCodesAndNotTheOrderOfTheEnum() {
        // The enum lists identity:* first and so on by declaration; the report sorts by the text.
        List<String> codes = viewOf("super_admin").permissions();

        assertThat(codes).isSortedAccordingTo(Comparator.naturalOrder());
    }

    private RolePermissionsView viewOf(String code) {
        return list.execute().stream().filter(view -> view.code().equals(code)).findFirst()
                .orElseThrow();
    }
}
