package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
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
        String generated = normalizedDocumentOf(appProfile);
        Path snapshot = SNAPSHOT_DIRECTORY.resolve(fileNameOf(appProfile));
        Files.createDirectories(GENERATED_DIRECTORY);
        Files.writeString(GENERATED_DIRECTORY.resolve(fileNameOf(appProfile)), generated,
                StandardCharsets.UTF_8);

        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Files.createDirectories(SNAPSHOT_DIRECTORY);
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
                    firstDifference(parse(approved), parse(generated), "$"),
                    GENERATED_DIRECTORY.resolve(fileNameOf(appProfile)), UPDATE_PROPERTY);
        }
    }

    @Test
    void theTwoProcessesPublishTwoDistinctDocumentsAndThePortalServesNoAdminPath() {
        Map<String, Object> admin = parse(normalizedDocumentOf("admin"));
        Map<String, Object> portal = parse(normalizedDocumentOf("portal"));

        assertThat(portal).isNotEqualTo(admin);
        assertThat(titleOf(admin)).isEqualTo(ConfiaApplication.ADMIN_API_TITLE);
        assertThat(titleOf(portal)).isEqualTo(ConfiaApplication.PORTAL_API_TITLE);
        // Disjointness, not doesNotContainAnyElementsOf: AssertJ rejects an empty iterable there,
        // and today both documents have zero paths. The check becomes meaningful with the first
        // controller, and until then it is vacuous by construction, which is said here.
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

        assertThat(asMap(problemDetail.get("properties")))
                .containsOnlyKeys("type", "title", "status", "detail", "instance", "traceId");
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
