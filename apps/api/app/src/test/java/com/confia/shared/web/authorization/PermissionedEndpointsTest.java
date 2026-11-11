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
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/students", Permission.STUDENTS_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/students/{id}", Permission.STUDENTS_READ));
        expected.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/students", Permission.STUDENTS_CREATE));
        expected.add(new PermissionedEndpoint(HttpMethod.PUT, "/api/v1/students/{id}", Permission.STUDENTS_UPDATE));

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
