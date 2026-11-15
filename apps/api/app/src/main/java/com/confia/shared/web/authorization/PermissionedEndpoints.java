package com.confia.shared.web.authorization;

import com.confia.kernel.Permission;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * The closed, immutable list of routes of the administrative chain that need a permission, parallel to
 * {@code PublicEndpoints} and {@code AuthenticatedEndpoints} and defined in this one place
 * (rbac-permission-matrix-and-audit-integration design.md D6). A route on no list is denied, so adding a
 * controller never opens a route by itself.
 *
 * <p>There is one instance per process: the security chain decides over it, and the bearer filter
 * derives from the same instance the routes whose session must be alive, so there is no second list
 * that can drift from the first. The public constructor exists for the test harnesses, which add
 * routes of their own and nothing else.
 */
public final class PermissionedEndpoints {

    private final List<PermissionedEndpoint> endpoints;

    public PermissionedEndpoints(List<PermissionedEndpoint> endpoints) {
        this.endpoints = List.copyOf(endpoints);
    }

    /**
     * The list of the administrative process: the route that reports which role can do what, which
     * needs {@code identity:read} (design.md D16), and the twelve routes of the academic structure of
     * {@code organization}: {@code GET} needs {@code organization:read}, {@code POST} needs {@code
     * organization:create} and {@code PUT} needs {@code organization:update}, and the four routes of the
     * student registry: the list and the detail need {@code students:read}, {@code POST} needs {@code
     * students:create} and {@code PUT} needs {@code students:update}, and the six routes of the enrollments: the reads need {@code students:read}, the
     * enrollment {@code students:create} and the transfer and the exit {@code students:update}, and the six routes of
     * the guardians: the reads need {@code guardians:read}, the creation {@code guardians:create} and the edit and the
     * replacement of the guardians of a student {@code guardians:update}, and the two routes of the consent of a
     * guardian for communications: the read needs {@code guardians:read} and the recording {@code guardians:update},
     * and the three routes of the student import: the simulation and the application need {@code students:create}
     * and the history of batches {@code students:read}. A route of a later
     * change is added here, in the same commit as its controller.
     */
    public static PermissionedEndpoints forAdmin() {
        List<PermissionedEndpoint> endpoints = new ArrayList<>();
        endpoints.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/identity/roles",
                Permission.IDENTITY_READ));
        for (String resource : List.of("academic-years", "modalities", "grades", "sections")) {
            String collection = "/api/v1/organization/" + resource;
            endpoints.add(new PermissionedEndpoint(HttpMethod.GET, collection,
                    Permission.ORGANIZATION_READ));
            endpoints.add(new PermissionedEndpoint(HttpMethod.POST, collection,
                    Permission.ORGANIZATION_CREATE));
            endpoints.add(new PermissionedEndpoint(HttpMethod.PUT, collection + "/{id}",
                    Permission.ORGANIZATION_UPDATE));
        }
        endpoints.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/students", Permission.STUDENTS_READ));
        endpoints.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/students/{id}",
                Permission.STUDENTS_READ));
        endpoints.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/students",
                Permission.STUDENTS_CREATE));
        endpoints.add(new PermissionedEndpoint(HttpMethod.PUT, "/api/v1/students/{id}",
                Permission.STUDENTS_UPDATE));
        endpoints.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/enrollments", Permission.STUDENTS_READ));
        endpoints.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/enrollments/{id}",
                Permission.STUDENTS_READ));
        endpoints.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/students/{studentId}/enrollments",
                Permission.STUDENTS_READ));
        endpoints.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/enrollments",
                Permission.STUDENTS_CREATE));
        endpoints.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/enrollments/{id}/transfer",
                Permission.STUDENTS_UPDATE));
        endpoints.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/enrollments/{id}/exit",
                Permission.STUDENTS_UPDATE));
        endpoints.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/guardians", Permission.GUARDIANS_READ));
        endpoints.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/guardians/{id}",
                Permission.GUARDIANS_READ));
        endpoints.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/guardians",
                Permission.GUARDIANS_CREATE));
        endpoints.add(new PermissionedEndpoint(HttpMethod.PUT, "/api/v1/guardians/{id}",
                Permission.GUARDIANS_UPDATE));
        endpoints.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/guardians/{id}/consents",
                Permission.GUARDIANS_READ));
        endpoints.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/guardians/{id}/consents",
                Permission.GUARDIANS_UPDATE));
        endpoints.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/students/{studentId}/guardians",
                Permission.GUARDIANS_READ));
        endpoints.add(new PermissionedEndpoint(HttpMethod.PUT, "/api/v1/students/{studentId}/guardians",
                Permission.GUARDIANS_UPDATE));
        endpoints.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/imports/students/simulate",
                Permission.STUDENTS_CREATE));
        endpoints.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/imports/students/apply",
                Permission.STUDENTS_CREATE));
        endpoints.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/imports/guardians/simulate",
                Permission.GUARDIANS_CREATE));
        endpoints.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/imports/guardians/apply",
                Permission.GUARDIANS_CREATE));
        // The links are guardians' links that this import creates for students: it needs both permissions.
        endpoints.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/imports/guardian-links/simulate",
                Permission.GUARDIANS_CREATE, List.of(Permission.GUARDIANS_UPDATE)));
        endpoints.add(new PermissionedEndpoint(HttpMethod.POST, "/api/v1/imports/guardian-links/apply",
                Permission.GUARDIANS_CREATE, List.of(Permission.GUARDIANS_UPDATE)));
        // The history needs students:read here; the controller adds guardians:read for the batches of the guardians
        // kinds (a batch of those kinds is listed only to an account that holds it).
        endpoints.add(new PermissionedEndpoint(HttpMethod.GET, "/api/v1/imports", Permission.STUDENTS_READ));
        return new PermissionedEndpoints(endpoints);
    }

    /** The routes, in a list that cannot be modified. */
    public List<PermissionedEndpoint> endpoints() {
        return endpoints;
    }
}
