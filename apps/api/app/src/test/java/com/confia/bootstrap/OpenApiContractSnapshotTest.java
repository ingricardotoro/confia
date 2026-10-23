package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.confia.shared.web.openapi.ContractSchemas;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Specs/build-integrity, requirements "El OpenAPI generado coincide con la instantánea aprobada"
 * and "Esquemas transversales del contrato presentes desde el primer documento"
 * (frontend-monorepo-and-contracts-pipeline, design.md decisions 1, 2 and 4; tasks 1.3 and 1.4).
 *
 * <p>Each web process publishes its own document, generated from its real entry point with the
 * {@code local} profile. The document is normalized (keys sorted, fixed indentation, LF line
 * endings, no {@code servers} block, which depends on the random port) and compared byte for byte
 * with the approved snapshot committed under {@code apps/api/openapi/}.
 *
 * <p><b>This test never overwrites a snapshot on its own.</b> Updating one takes the explicit
 * {@value #UPDATE_PROPERTY} property, and even then the test fails, so the flag can never be left
 * on in CI to approve a contract change unseen. The generated document is always written to
 * {@code target/openapi/}, which CI publishes as an artifact.
 */
class OpenApiContractSnapshotTest {

    static final String UPDATE_PROPERTY = "confia.openapi.update";

    private static final Path SNAPSHOT_DIRECTORY = Path.of("..", "openapi");
    private static final Path GENERATED_DIRECTORY = Path.of("target", "openapi");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"admin", "portal"})
    void eachProcessPublishesExactlyItsApprovedSnapshot(String appProfile) throws IOException {
        verifyAgainstSnapshot(appProfile, normalizedDocumentOf(appProfile), SNAPSHOT_DIRECTORY,
                GENERATED_DIRECTORY, Boolean.getBoolean(UPDATE_PROPERTY));
    }

    /**
     * The negative control of the gate above (ADR-0018: a check that never fails protects nothing;
     * task 1.4's mandatory demonstration, kept as a permanent test instead of a one-off run). The
     * same comparison, over a copy of the approved snapshot altered by hand in a temporary
     * directory: it must fail naming the document and the first JSON path that differs, and it
     * must leave the altered snapshot exactly as it found it.
     */
    @Test
    void aSnapshotAlteredByHandFailsNamingTheFirstDifferenceAndIsLeftUntouched(
            @TempDir Path temporary) throws IOException {
        String generated = Files.readString(SNAPSHOT_DIRECTORY.resolve(fileNameOf("admin")),
                StandardCharsets.UTF_8);
        String altered = generated.replace("\"CONFIA admin API\"", "\"Tampered title\"");
        assertThat(altered).as("the alteration must actually change the snapshot")
                .isNotEqualTo(generated);
        Path snapshotDirectory = Files.createDirectories(temporary.resolve("openapi"));
        Path snapshot = snapshotDirectory.resolve(fileNameOf("admin"));
        Files.writeString(snapshot, altered, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifyAgainstSnapshot("admin", generated, snapshotDirectory,
                temporary.resolve("generated"), false))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("the admin OpenAPI document differs")
                .hasMessageContaining("first at $.info.title");
        assertThat(Files.readString(snapshot, StandardCharsets.UTF_8))
                .as("the comparison must never rewrite the snapshot")
                .isEqualTo(altered);
    }

    /** Updating a snapshot rewrites it and still fails, so the flag can never pass silently. */
    @Test
    void theUpdateFlagRewritesTheSnapshotAndStillFails(@TempDir Path temporary)
            throws IOException {
        String generated = Files.readString(SNAPSHOT_DIRECTORY.resolve(fileNameOf("admin")),
                StandardCharsets.UTF_8);
        Path snapshotDirectory = Files.createDirectories(temporary.resolve("openapi"));
        Path snapshot = snapshotDirectory.resolve(fileNameOf("admin"));
        Files.writeString(snapshot, "{}\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifyAgainstSnapshot("admin", generated, snapshotDirectory,
                temporary.resolve("generated"), true))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("was rewritten because -D" + UPDATE_PROPERTY + "=true");
        assertThat(Files.readString(snapshot, StandardCharsets.UTF_8)).isEqualTo(generated);
    }

    /**
     * The gate itself. Always writes the generated document to {@code generatedDirectory}; with
     * {@code update}, rewrites the snapshot and fails; without a snapshot, fails printing the
     * generated document so it can be reviewed and committed; otherwise compares byte for byte and,
     * on a difference, fails naming only the first JSON path that differs.
     */
    private static void verifyAgainstSnapshot(String appProfile, String generated,
            Path snapshotDirectory, Path generatedDirectory, boolean update) throws IOException {
        Path snapshot = snapshotDirectory.resolve(fileNameOf(appProfile));
        Path generatedFile = generatedDirectory.resolve(fileNameOf(appProfile));
        Files.createDirectories(generatedDirectory);
        Files.writeString(generatedFile, generated, StandardCharsets.UTF_8);

        if (update) {
            Files.createDirectories(snapshotDirectory);
            Files.writeString(snapshot, generated, StandardCharsets.UTF_8);
            fail("%s was rewritten because -D%s=true is set; review the diff, commit it and run "
                    + "again without the property", snapshot, UPDATE_PROPERTY);
        }
        if (!Files.exists(snapshot)) {
            // The first snapshot of a process: printed whole so it can be reviewed and committed.
            // The document carries no secret; it is the public contract of the process.
            fail("no approved snapshot at %s yet. Generated document, to review and commit:%n%s",
                    snapshot, generated);
        }
        String approved = Files.readString(snapshot, StandardCharsets.UTF_8);
        if (!approved.equals(generated)) {
            fail("the %s OpenAPI document differs from its approved snapshot %s, first at %s. The "
                    + "generated document is in %s; if the change is intended, update the "
                    + "snapshot with -D%s=true and commit it", appProfile, snapshot,
                    firstDifference(parse(approved), parse(generated), "$"), generatedFile,
                    UPDATE_PROPERTY);
        }
    }

    @Test
    void theTwoProcessesPublishTwoDistinctDocumentsAndThePortalServesNoAdminPath() {
        Map<String, Object> admin = parse(normalizedDocumentOf("admin"));
        Map<String, Object> portal = parse(normalizedDocumentOf("portal"));

        assertThat(portal).isNotEqualTo(admin);
        assertThat(titleOf(admin)).isEqualTo(ConfiaApplication.ADMIN_API_TITLE);
        assertThat(titleOf(portal)).isEqualTo(ConfiaApplication.PORTAL_API_TITLE);
        // Disjointness, not doesNotContainAnyElementsOf: AssertJ rejects an empty iterable there.
        // Non-vacuous since the first controller: the administrative document holds a path (the
        // portal still holds none, so the check says that no path of the one is in the other).
        assertThat(pathsOf(admin)).as("non-vacuous: the administrative document has its operation")
                .isNotEmpty();
        assertThat(Collections.disjoint(pathsOf(portal), pathsOf(admin)))
                .as("no operation of the administrative process may be served by the portal")
                .isTrue();
    }

    @Test
    void theNormalizedDocumentIsDeterministic() {
        assertThat(normalizedDocumentOf("admin"))
                .as("two generations of the same process must give the same bytes (probe S2)")
                .isEqualTo(normalizedDocumentOf("admin"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "portal"})
    void moneyTravelsAsTwoRequiredStringsAndNeverAsANumber(String appProfile) {
        Map<String, Object> money = schemaOf(parse(normalizedDocumentOf(appProfile)),
                ContractSchemas.MONEY);

        Map<String, Object> properties = asMap(money.get("properties"));
        assertThat(properties).containsOnlyKeys("amount", "currency");
        assertThat(typeOf(asMap(properties.get("amount")))).isEqualTo("string");
        assertThat(typeOf(asMap(properties.get("currency")))).isEqualTo("string");
        assertThat(asList(money.get("required"))).containsExactlyInAnyOrder("amount", "currency");
        assertThat(properties.values().stream().map(value -> typeOf(asMap(value))))
                .as("no property of the amount may be a JSON number")
                .doesNotContain("number", "integer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "portal"})
    void problemDetailDeclaresTheRfc9457FieldsAndTheTraceId(String appProfile) {
        Map<String, Object> problemDetail = schemaOf(parse(normalizedDocumentOf(appProfile)),
                ContractSchemas.PROBLEM_DETAIL);

        assertThat(asMap(problemDetail.get("properties"))).containsOnlyKeys("type", "title",
                "status", "detail", "instance", "traceId", "errors");
    }

    /**
     * WE38 and BI26: {@code errors}, the RFC 9457 extension member of a {@code validation-failed}
     * response, is declared by the schema in both documents: an optional array of objects with a
     * {@code field} and a {@code reason}, both text, and nothing else.
     */
    @ParameterizedTest
    @ValueSource(strings = {"admin", "portal"})
    void problemDetailDeclaresErrorsAsAnOptionalArrayOfFieldAndReason(String appProfile) {
        Map<String, Object> problemDetail = schemaOf(parse(normalizedDocumentOf(appProfile)),
                ContractSchemas.PROBLEM_DETAIL);

        Map<String, Object> errors = asMap(asMap(problemDetail.get("properties")).get("errors"));
        assertThat(typeOf(errors)).isEqualTo("array");
        Map<String, Object> item = asMap(errors.get("items"));
        assertThat(typeOf(item)).isEqualTo("object");
        Map<String, Object> itemProperties = asMap(item.get("properties"));
        assertThat(itemProperties).containsOnlyKeys("field", "reason");
        assertThat(typeOf(asMap(itemProperties.get("field")))).isEqualTo("string");
        assertThat(typeOf(asMap(itemProperties.get("reason")))).isEqualTo("string");
        assertThat(asList(item.get("required"))).containsExactlyInAnyOrder("field", "reason");
        assertThat(problemDetail.get("required"))
                .as("errors is optional: only a validation failure carries it").isNull();
    }

    // --- The first operation of production (decision 13; BI24 to BI27, WE39) ---

    private static final String SESSION_PATH = "/api/v1/auth/sessions/current";
    private static final String LOGIN_PATH = "/api/v1/auth/sessions";

    @Test
    void theAdministrativeDocumentHoldsExactlyTheLoginTheCurrentSessionAndTheEnrollmentOperations() {
        Map<String, Object> admin = parse(normalizedDocumentOf("admin"));

        assertThat(pathsOf(admin)).containsExactlyInAnyOrder(LOGIN_PATH, SESSION_PATH,
                "/api/v1/auth/mfa/enrollments");
        for (String route : List.of("/api/v1/auth/mfa/enrollments")) {
            Map<String, Object> post = asMap(asMap(asMap(admin.get("paths")).get(route)).get("post"));
            assertThat(asList(post.get("security"))).as("a restricted token in the bearer header").hasSize(1);
            assertThat(asMap(post.get("responses"))).containsKeys("401", "403", "429");
        }
        Map<String, Object> path = asMap(asMap(admin.get("paths")).get(SESSION_PATH));
        assertThat(path).as("one operation: GET, and no other method").containsOnlyKeys("get");
        Map<String, Object> operation = asMap(path.get("get"));
        assertThat(asMap(operation.get("responses")))
                .as("exactly the approved responses: no 403, no 429, nothing the translator adds")
                .containsOnlyKeys("200", "401", "500");
        assertThat(asList(operation.get("security"))).hasSize(1);
        assertThat(asMap(asList(operation.get("security")).get(0))).containsOnlyKeys("bearerAuth");
        assertThat(operation.get("parameters")).as("no parameter: the actor is the token").isNull();
        assertThat(operation.get("requestBody")).as("a read has no body").isNull();
    }

    @Test
    void theErrorResponsesArePlainProblemDetailsAndTheSuccessIsTheDtoInJson() {
        Map<String, Object> responses = asMap(asMap(asMap(asMap(parse(
                normalizedDocumentOf("admin")).get("paths")).get(SESSION_PATH)).get("get"))
                .get("responses"));

        for (String code : List.of("401", "500")) {
            Map<String, Object> content = asMap(asMap(responses.get(code)).get("content"));
            assertThat(content).as("the %s response", code).containsOnlyKeys("application/problem+json");
            assertThat(asMap(asMap(content.get("application/problem+json")).get("schema")))
                    .containsEntry("$ref", "#/components/schemas/ProblemDetail");
        }
        Map<String, Object> success = asMap(asMap(responses.get("200")).get("content"));
        assertThat(success).containsOnlyKeys("application/json");
        assertThat(asMap(asMap(success.get("application/json")).get("schema")))
                .containsEntry("$ref", "#/components/schemas/CurrentSessionResponse");
    }

    @Test
    void theDtoSchemaIsTheClosedListOfSevenRequiredMembers() {
        Map<String, Object> dto = schemaOf(parse(normalizedDocumentOf("admin")),
                "CurrentSessionResponse");

        Map<String, Object> properties = asMap(dto.get("properties"));
        assertThat(properties).containsOnlyKeys("sessionId", "accountId", "institutionId",
                "authenticationMethods", "accessTokenExpiresAt", "sessionExpiresAt",
                "idleExpiresAt");
        assertThat(asList(dto.get("required"))).containsExactlyInAnyOrder("sessionId", "accountId",
                "institutionId", "authenticationMethods", "accessTokenExpiresAt",
                "sessionExpiresAt", "idleExpiresAt");
        for (String instant : List.of("accessTokenExpiresAt", "sessionExpiresAt", "idleExpiresAt")) {
            assertThat(asMap(properties.get(instant))).containsEntry("format", "date-time");
        }
        assertThat(asMap(properties.get("authenticationMethods"))).containsEntry("type", "array");
    }

    @Test
    void theBearerSchemeIsDeclaredAsHttpBearerJwtInTheAdministrativeDocument() {
        Map<String, Object> components = asMap(parse(normalizedDocumentOf("admin"))
                .get("components"));

        assertThat(asMap(asMap(components.get("securitySchemes")).get("bearerAuth")))
                .containsEntry("type", "http").containsEntry("scheme", "bearer")
                .containsEntry("bearerFormat", "JWT");
    }

    /** BI25 and BI27: the login is one POST with exactly the seven approved responses. */
    @Test
    void theLoginIsOnePostWithExactlyTheSevenApprovedResponsesAndNoSecurityRequirement() {
        Map<String, Object> admin = parse(normalizedDocumentOf("admin"));
        Map<String, Object> path = asMap(asMap(admin.get("paths")).get(LOGIN_PATH));

        assertThat(path).as("one operation: POST, and no other method").containsOnlyKeys("post");
        Map<String, Object> operation = asMap(path.get("post"));
        assertThat(asMap(operation.get("responses")))
                .as("exactly the approved responses: no 500, nothing the translator adds")
                .containsOnlyKeys("200", "400", "401", "403", "415", "429", "503");
        assertThat(operation.get("security")).as("the login needs no credential").isNull();
        assertThat(operation.get("parameters")).as("no header and no parameter").isNull();
        Map<String, Object> body = asMap(asMap(asMap(operation.get("requestBody")).get("content"))
                .get("application/json"));
        assertThat(asMap(body.get("schema")))
                .containsEntry("$ref", "#/components/schemas/LoginRequest");
        assertThat(asMap(asMap(operation.get("requestBody")).get("content")))
                .containsOnlyKeys("application/json");
    }

    @Test
    void theLoginErrorsArePlainProblemDetailsAndTheSuccessIsAOneOfOnTheStatusDiscriminator() {
        Map<String, Object> admin = parse(normalizedDocumentOf("admin"));
        Map<String, Object> responses = asMap(asMap(asMap(asMap(admin.get("paths"))
                .get(LOGIN_PATH)).get("post")).get("responses"));

        for (String code : List.of("400", "401", "403", "415", "429", "503")) {
            Map<String, Object> content = asMap(asMap(responses.get(code)).get("content"));
            assertThat(content).as("the %s response", code)
                    .containsOnlyKeys("application/problem+json");
            assertThat(asMap(asMap(content.get("application/problem+json")).get("schema")))
                    .containsEntry("$ref", "#/components/schemas/ProblemDetail");
        }
        assertThat(asMap(asMap(responses.get("401")).get("headers"))).containsOnlyKeys("WWW-Authenticate");
        assertThat(asMap(asMap(responses.get("429")).get("headers"))).containsOnlyKeys("Retry-After");
        Map<String, Object> success = asMap(asMap(responses.get("200")).get("content"));
        assertThat(success).containsOnlyKeys("application/json");
        assertThat(asMap(asMap(success.get("application/json")).get("schema")))
                .containsEntry("$ref", "#/components/schemas/LoginResponse");
        Map<String, Object> login = schemaOf(admin, "LoginResponse");
        assertThat(asList(login.get("oneOf"))).extracting(item -> asMap(item).get("$ref"))
                .containsExactlyInAnyOrder("#/components/schemas/AuthenticatedSessionResponse",
                        "#/components/schemas/SecondFactorPendingResponse");
        assertThat(asMap(login.get("discriminator"))).containsEntry("propertyName", "status");
    }

    @Test
    void theLoginSchemasAreTheClosedListsOfMembersTheContractDeclares() {
        Map<String, Object> admin = parse(normalizedDocumentOf("admin"));

        assertThat(asMap(schemaOf(admin, "LoginRequest").get("properties")))
                .containsOnlyKeys("identifier", "password");
        assertThat(asList(schemaOf(admin, "LoginRequest").get("required")))
                .containsExactlyInAnyOrder("identifier", "password");
        assertThat(asMap(schemaOf(admin, "AuthenticatedSessionResponse").get("properties")))
                .containsOnlyKeys("status", "accessToken", "accessTokenExpiresAt",
                        "sessionExpiresAt", "idleExpiresAt");
        assertThat(asMap(schemaOf(admin, "SecondFactorPendingResponse").get("properties")))
                .containsOnlyKeys("status", "secondFactorToken", "secondFactorTokenExpiresAt");
        assertThat(asList(asMap(asMap(schemaOf(admin, "SecondFactorPendingResponse")
                .get("properties")).get("status")).get("enum")))
                .containsExactlyInAnyOrder("second-factor-required",
                        "second-factor-enrollment-required");
    }

    /** BI27. */
    @Test
    void thePortalDocumentStillHoldsNoOperation() {
        assertThat(pathsOf(parse(normalizedDocumentOf("portal")))).isEmpty();
    }

    /**
     * BI25, the negative control: the document of a route to which the translator added a response
     * nobody approved (a {@code 403} and a {@code 429} here) fails the comparison naming that
     * response, so a response that appears without a visible edit of the snapshot cannot pass.
     */
    @ParameterizedTest
    @ValueSource(strings = {"403", "429"})
    void anUnapprovedResponseOfTheTranslatorFailsNamingTheAddedResponse(String code,
            @TempDir Path temporary) throws IOException {
        Map<String, Object> altered = parse(Files.readString(
                SNAPSHOT_DIRECTORY.resolve(fileNameOf("admin")), StandardCharsets.UTF_8));
        asMap(asMap(asMap(asMap(altered.get("paths")).get(SESSION_PATH)).get("get"))
                .get("responses")).put(code, new TreeMap<String, Object>(
                        Map.of("description", "added by the translator")));
        String generated = JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(sorted(altered)).replace("\r\n", "\n") + "\n";
        Path snapshotDirectory = Files.createDirectories(temporary.resolve("openapi"));
        Files.writeString(snapshotDirectory.resolve(fileNameOf("admin")),
                Files.readString(SNAPSHOT_DIRECTORY.resolve(fileNameOf("admin")),
                        StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifyAgainstSnapshot("admin", generated, snapshotDirectory,
                temporary.resolve("generated"), false))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("first at $.paths." + SESSION_PATH + ".get.responses." + code);
    }

    private static String normalizedDocumentOf(String appProfile) {
        try (OpenApiProcess process = OpenApiProcess.start(appProfile, "local")) {
            HttpResponse<String> response = process.get(OpenApiProcess.API_DOCS_PATH);
            assertThat(response.statusCode()).isEqualTo(200);
            Map<String, Object> document = parse(response.body());
            document.remove("servers");
            return JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(sorted(document))
                    .replace("\r\n", "\n") + "\n";
        }
    }

    /** Recursively rebuilds every JSON object as a {@link TreeMap}, so keys come out sorted. */
    private static Object sorted(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), sorted(value)));
            return result;
        }
        if (node instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(value -> result.add(sorted(value)));
            return result;
        }
        return node;
    }

    /** The JSON path of the first difference, never the documents themselves. */
    private static String firstDifference(Object approved, Object generated, String path) {
        if (approved instanceof Map<?, ?> left && generated instanceof Map<?, ?> right) {
            Set<Object> keys = new TreeSet<>(
                    (a, b) -> String.valueOf(a).compareTo(String.valueOf(b)));
            keys.addAll(left.keySet());
            keys.addAll(right.keySet());
            for (Object key : keys) {
                if (!left.containsKey(key) || !right.containsKey(key)) {
                    return path + "." + key;
                }
                String inner = firstDifference(left.get(key), right.get(key), path + "." + key);
                if (inner != null) {
                    return inner;
                }
            }
            return null;
        }
        if (approved instanceof List<?> left && generated instanceof List<?> right) {
            for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
                if (i >= left.size() || i >= right.size()) {
                    return path + "[" + i + "]";
                }
                String inner = firstDifference(left.get(i), right.get(i), path + "[" + i + "]");
                if (inner != null) {
                    return inner;
                }
            }
            return null;
        }
        return Objects.equals(approved, generated) ? null : path;
    }

    private static Map<String, Object> schemaOf(Map<String, Object> document, String name) {
        Map<String, Object> schemas = asMap(asMap(document.get("components")).get("schemas"));
        assertThat(schemas).as("the %s schema must be published", name).containsKey(name);
        return asMap(schemas.get(name));
    }

    /** In OpenAPI 3.1 a schema's type may be written as a string or as a one-element array. */
    private static String typeOf(Map<String, Object> schema) {
        Object type = schema.get("type");
        if (type instanceof List<?> list && list.size() == 1) {
            return String.valueOf(list.get(0));
        }
        return String.valueOf(type);
    }

    private static String titleOf(Map<String, Object> document) {
        return String.valueOf(asMap(document.get("info")).get("title"));
    }

    private static Set<String> pathsOf(Map<String, Object> document) {
        Object paths = document.get("paths");
        return paths == null ? Set.of() : asMap(paths).keySet();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        return JSON.readValue(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }

    private static String fileNameOf(String appProfile) {
        return appProfile + ".openapi.json";
    }
}
