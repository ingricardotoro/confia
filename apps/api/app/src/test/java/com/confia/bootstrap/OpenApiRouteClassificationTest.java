package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.authentication.AuthenticatedEndpoints;
import com.confia.shared.web.authorization.PermissionedEndpoints;
import com.confia.shared.web.edge.PublicEndpoints;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scenario AZ38 (rbac-permission-matrix-and-audit-integration design.md D13): the operations of the
 * OpenAPI document of the administrative process, read from the document the real process serves, are
 * each on one of the four lists of the chain. It is independent of the start guard, which reads the
 * handler mappings: this one reads what a client is told exists. The negative control feeds the same
 * check a document with an operation on no list, and the check names it.
 */
class OpenApiRouteClassificationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete",
            "options", "head", "patch", "trace");

    /** Every list of the process, as {@code METHOD pattern}, with the documentation routes left out. */
    private static Set<String> classified() {
        Set<String> all = new TreeSet<>();
        PublicEndpoints.forAdmin(new MockEnvironment()).endpoints()
                .forEach(entry -> all.add(entry.method() + " " + entry.pattern()));
        AuthenticatedEndpoints authenticated = AuthenticatedEndpoints.forAdmin();
        authenticated.endpoints().forEach(entry -> all.add(entry.method() + " " + entry.pattern()));
        authenticated.secondFactorEndpoints()
                .forEach(entry -> all.add(entry.method() + " " + entry.pattern()));
        PermissionedEndpoints.forAdmin().endpoints()
                .forEach(entry -> all.add(entry.method() + " " + entry.pattern()));
        return all;
    }

    /** The operations of {@code document} as {@code METHOD path}. */
    static Set<String> operationsOf(JsonNode document) {
        Set<String> operations = new TreeSet<>();
        document.get("paths").properties().forEach(path -> path.getValue().properties()
                .stream().filter(operation -> HTTP_METHODS.contains(operation.getKey()))
                .forEach(operation -> operations.add(
                        operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey())));
        return operations;
    }

    /** One sentence per operation of {@code document} that is on none of {@code classified}. */
    static List<String> unclassified(JsonNode document, Set<String> classified) {
        List<String> problems = new ArrayList<>();
        for (String operation : operationsOf(document)) {
            if (!classified.contains(operation)) {
                problems.add("operation " + operation + " of the OpenAPI document is on no list");
            }
        }
        return problems;
    }

    @Test
    void everyOperationOfTheRealDocumentIsOnOneOfTheFourLists() {
        try (OpenApiProcess process = OpenApiProcess.start("admin", "local")) {
            JsonNode document = JSON.readTree(process.get(OpenApiProcess.API_DOCS_PATH).body());

            assertThat(operationsOf(document)).as("non-vacuous: the document describes operations")
                    .contains("POST /api/v1/auth/sessions", "GET /api/v1/auth/sessions/current")
                    .hasSizeGreaterThanOrEqualTo(5);
            assertThat(unclassified(document, classified())).isEmpty();
        }
    }

    @Test
    void anOperationOnNoListIsNamedByMethodAndPath() {
        JsonNode document = JSON.readTree("""
                {"paths": {
                  "/api/v1/auth/sessions": {"post": {}},
                  "/api/v1/invoices/{id}": {"get": {}, "parameters": []}
                }}""");

        assertThat(unclassified(document, classified())).containsExactly(
                "operation GET /api/v1/invoices/{id} of the OpenAPI document is on no list");
    }

    @Test
    void theSamePathUnderAnotherMethodIsAnotherOperation() {
        JsonNode document = JSON.readTree("""
                {"paths": {"/api/v1/auth/sessions": {"post": {}, "put": {}}}}""");

        assertThat(unclassified(document, classified())).containsExactly(
                "operation PUT /api/v1/auth/sessions of the OpenAPI document is on no list");
    }
}
