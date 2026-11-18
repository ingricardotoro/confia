package com.confia.shared.web.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.Permission;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The closed list of permissioned routes (rbac-permission-matrix-and-audit-integration design.md D6
 * and D16; scenario AZ29): in production it is exactly the one route that reports the roles, and a
 * list cannot be changed after it is built.
 */
class PermissionedEndpointsTest {

    private static final PermissionedEndpoint PAY = new PermissionedEndpoint(HttpMethod.POST,
            "/test/pay", Permission.PAYMENTS_CREATE);

    @Test
    void theProductionListIsTheReportOfTheRolesAndTheTwelveRoutesOfTheAcademicStructure() {
        List<PermissionedEndpoint> expected = new ArrayList<>();
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/identity/roles",
                Permission.IDENTITY_READ));
        for (String resource : List.of("academic-years", "modalities", "grades", "sections")) {
            String collection = "/api/v1/organization/" + resource;
            expected.add(new PermissionedEndpoint(HttpMethod.GET, collection,
                    Permission.ORGANIZATION_READ));
            expected.add(new PermissionedEndpoint(HttpMethod.POST, collection,
                    Permission.ORGANIZATION_CREATE));
            expected.add(new PermissionedEndpoint(HttpMethod.PUT, collection + "/{id}",
                    Permission.ORGANIZATION_UPDATE));
        }
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/catalog/payment-concepts",
                Permission.CATALOG_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/catalog/payment-concepts",
                Permission.CATALOG_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.PUT, "/api/v1/catalog/payment-concepts/{id}",
                Permission.CATALOG_UPDATE));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/catalog/price-lists",
                Permission.CATALOG_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/catalog/price-lists",
                Permission.CATALOG_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/catalog/price-lists/{id}",
                Permission.CATALOG_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.PUT, "/api/v1/catalog/price-lists/{id}",
                Permission.CATALOG_UPDATE));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/catalog/price-lists/{id}/items",
                Permission.CATALOG_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/catalog/tax-rates",
                Permission.CATALOG_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/catalog/tax-rates",
                Permission.CATALOG_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/catalog/tax-rates/{id}",
                Permission.CATALOG_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.PUT, "/api/v1/catalog/tax-rates/{id}",
                Permission.CATALOG_UPDATE));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/catalog/tax-rates/{id}/periods",
                Permission.CATALOG_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/charges/plans", Permission.CHARGES_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/charges/plans", Permission.CHARGES_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/charges/plans/{id}",
                Permission.CHARGES_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.PUT, "/api/v1/charges/plans/{id}",
                Permission.CHARGES_UPDATE));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/charges/plans/{id}/items",
                Permission.CHARGES_UPDATE));
        expected.add(new PermissionedEndpoint(HttpMethod.DELETE, "/api/v1/charges/plans/{id}/items/{itemId}",
                Permission.CHARGES_UPDATE));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/charges/plans/{id}/accrual-periods",
                Permission.CHARGES_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/charges/plans/{id}/accrual-preview",
                Permission.CHARGES_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/charges/plans/{id}/accrual-runs",
                Permission.CHARGES_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/charges/accrual-runs", Permission.CHARGES_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/charges/accrual-runs/{runId}",
                Permission.CHARGES_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/charges/students/{studentId}/statement",
                Permission.CHARGES_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/charges/students/{studentId}/charges",
                Permission.CHARGES_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/charges/{chargeId}/void",
                Permission.CHARGES_VOID));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/charges/integrity-runs",
                Permission.CHARGES_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/charges/integrity-runs/latest",
                Permission.CHARGES_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/charges/integrity-runs/{runId}",
                Permission.CHARGES_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/charges/posting-accounts",
                Permission.CHARGES_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.PUT, "/api/v1/charges/posting-accounts",
                Permission.LEDGER_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/ledger/accounts", Permission.LEDGER_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/ledger/accounts",
                Permission.LEDGER_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.PUT, "/api/v1/ledger/accounts/{id}",
                Permission.LEDGER_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/students", Permission.STUDENTS_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/students/{id}", Permission.STUDENTS_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/students", Permission.STUDENTS_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.PUT, "/api/v1/students/{id}", Permission.STUDENTS_UPDATE));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/enrollments", Permission.STUDENTS_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/enrollments/{id}", Permission.STUDENTS_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/students/{studentId}/enrollments",
                Permission.STUDENTS_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/enrollments", Permission.STUDENTS_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/enrollments/{id}/transfer",
                Permission.STUDENTS_UPDATE));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/enrollments/{id}/exit",
                Permission.STUDENTS_UPDATE));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/guardians", Permission.GUARDIANS_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/guardians/{id}", Permission.GUARDIANS_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/guardians", Permission.GUARDIANS_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.PUT, "/api/v1/guardians/{id}", Permission.GUARDIANS_UPDATE));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/guardians/{id}/consents",
                Permission.GUARDIANS_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/guardians/{id}/consents",
                Permission.GUARDIANS_UPDATE));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/students/{studentId}/guardians",
                Permission.GUARDIANS_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.PUT, "/api/v1/students/{studentId}/guardians",
                Permission.GUARDIANS_UPDATE));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/imports/students/simulate",
                Permission.STUDENTS_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/imports/students/apply",
                Permission.STUDENTS_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/imports/guardians/simulate",
                Permission.GUARDIANS_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/imports/guardians/apply",
                Permission.GUARDIANS_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/imports/guardian-links/simulate",
                Permission.GUARDIANS_CREATE, List.of(Permission.GUARDIANS_UPDATE)));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/imports/guardian-links/apply",
                Permission.GUARDIANS_CREATE, List.of(Permission.GUARDIANS_UPDATE)));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/imports/enrollments/simulate",
                Permission.STUDENTS_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/imports/enrollments/apply",
                Permission.STUDENTS_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/imports", Permission.STUDENTS_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/imports/reconciliations",
                Permission.STUDENTS_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/imports/reconciliations/preview",
                Permission.STUDENTS_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/imports/reconciliations",
                Permission.STUDENTS_READ));

        assertThat(PermissionedEndpoints.forAdmin().endpoints()).containsExactlyElementsOf(expected);
    }

    @Test
    void aListHoldsExactlyTheRoutesItWasGiven() {
        assertThat(new PermissionedEndpoints(List.of(PAY)).endpoints()).containsExactly(PAY);
    }

    @Test
    void theListIsImmutableAndDetachedFromTheOneItWasBuiltFrom() {
        List<PermissionedEndpoint> source = new ArrayList<>(List.of(PAY));
        PermissionedEndpoints endpoints = new PermissionedEndpoints(source);
        source.clear();

        assertThat(endpoints.endpoints()).containsExactly(PAY);
        assertThatThrownBy(() -> endpoints.endpoints().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anEntryRejectsAnyMissingMember() {
        assertThatThrownBy(() -> new PermissionedEndpoint(null, "/x", Permission.IDENTITY_READ))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PermissionedEndpoint(HttpMethod.GET, null, Permission.IDENTITY_READ))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PermissionedEndpoint(HttpMethod.GET, "/x", null))
                .isInstanceOf(NullPointerException.class);
    }
}
