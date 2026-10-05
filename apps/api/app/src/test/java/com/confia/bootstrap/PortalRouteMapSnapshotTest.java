package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.confia.bootstrap.RegisteredRoutes.Route;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * Specs/build-integrity, "Instantánea aprobada del mapa de rutas del portal" (web-edge-foundations
 * design.md, decision 21). The portal, started through its production entry point with the default
 * (production) configuration, must serve exactly the routes of {@code apps/api/routes/
 * portal.routes.json}, which is committed with the explicit content {@code {"process": "portal",
 * "routes": []}}. A route added to the portal breaks the build, naming the route.
 *
 * <p><b>This test never overwrites a snapshot on its own.</b> Updating one takes the explicit
 * {@value #UPDATE_PROPERTY} property, and even then the test fails, exactly like the OpenAPI
 * snapshot. The generated map is always written to {@code target/routes/}.
 */
class PortalRouteMapSnapshotTest {

    static final String UPDATE_PROPERTY = "confia.routes.update";

    private static final Path SNAPSHOT_DIRECTORY = Path.of("..", "routes");
    private static final Path GENERATED_DIRECTORY = Path.of("target", "routes");
    private static final String FILE = "portal.routes.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void thePortalServesExactlyItsApprovedRouteMap() throws IOException {
        List<Route> routes;
        try (OpenApiProcess portal = OpenApiProcess.start("portal")) {
            routes = RegisteredRoutes.of(portal.context());
        }

        verifyAgainstSnapshot(routes, SNAPSHOT_DIRECTORY, GENERATED_DIRECTORY,
                Boolean.getBoolean(UPDATE_PROPERTY));
    }

    @Test
    void theCommittedSnapshotIsTheExplicitEmptyMap() throws IOException {
        Map<String, Object> approved = parse(Files.readString(
                SNAPSHOT_DIRECTORY.resolve(FILE), StandardCharsets.UTF_8));

        assertThat(approved).containsOnlyKeys("process", "routes");
        assertThat(approved.get("process")).isEqualTo("portal");
        assertThat(approved.get("routes")).isEqualTo(List.of());
    }

    /**
     * The negative control (ADR-0018): the same comparison for a portal that serves a route the
     * snapshot does not know, which is what a controller added to the portal looks like. It must
     * fail naming the route, and leave the snapshot as it found it.
     */
    @Test
    void aRouteTheSnapshotDoesNotKnowFailsNamingTheRoute(@TempDir Path temporary)
            throws IOException {
        Path snapshot = copyOfTheSnapshot(temporary);

        assertThatThrownBy(() -> verifyAgainstSnapshot(
                List.of(new Route("GET", "/api/v1/guardians/{id}")), snapshot.getParent(),
                temporary.resolve("generated"), false))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("the portal route map differs")
                .hasMessageContaining("added: GET /api/v1/guardians/{id}");
        assertThat(Files.readString(snapshot, StandardCharsets.UTF_8))
                .as("the comparison never rewrites the snapshot")
                .isEqualTo(Files.readString(SNAPSHOT_DIRECTORY.resolve(FILE),
                        StandardCharsets.UTF_8));
    }

    @Test
    void aRouteTheSnapshotListsAndThePortalNoLongerServesFailsNamingTheRoute(
            @TempDir Path temporary) throws IOException {
        Path directory = Files.createDirectories(temporary.resolve("routes"));
        Files.writeString(directory.resolve(FILE), "{\"process\": \"portal\", \"routes\": "
                + "[{\"method\": \"GET\", \"pattern\": \"/old\"}]}", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifyAgainstSnapshot(List.of(), directory,
                temporary.resolve("generated"), false))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("removed: GET /old");
    }

    @Test
    void anAbsentSnapshotFailsPrintingTheGeneratedMapAndWritesNothingThere(
            @TempDir Path temporary) {
        Path directory = temporary.resolve("routes");

        assertThatThrownBy(() -> verifyAgainstSnapshot(List.of(), directory,
                temporary.resolve("generated"), false))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("no approved snapshot at")
                .hasMessageContaining("\"routes\" : [ ]");
        assertThat(directory).as("a missing snapshot is never created").doesNotExist();
    }

    /** Updating a snapshot rewrites it and still fails, so the flag can never pass silently. */
    @Test
    void theUpdateFlagRewritesTheSnapshotAndStillFails(@TempDir Path temporary)
            throws IOException {
        Path directory = Files.createDirectories(temporary.resolve("routes"));
        Files.writeString(directory.resolve(FILE), "{}\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifyAgainstSnapshot(List.of(), directory,
                temporary.resolve("generated"), true))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("was rewritten because -D" + UPDATE_PROPERTY + "=true");
        assertThat(parse(Files.readString(directory.resolve(FILE), StandardCharsets.UTF_8)))
                .containsEntry("process", "portal")
                .containsEntry("routes", List.of());
    }

    private static Path copyOfTheSnapshot(Path temporary) throws IOException {
        Path directory = Files.createDirectories(temporary.resolve("routes"));
        return Files.copy(SNAPSHOT_DIRECTORY.resolve(FILE), directory.resolve(FILE));
    }

    /**
     * The gate. Always writes the generated map to {@code generatedDirectory}; with {@code update},
     * rewrites the snapshot and fails; without a snapshot, fails printing the generated map; on a
     * difference, fails naming every route added and removed. The comparison is over the parsed
     * map, so the layout of the committed file does not matter, only its content.
     */
    private static void verifyAgainstSnapshot(List<Route> routes, Path snapshotDirectory,
            Path generatedDirectory, boolean update) throws IOException {
        Path snapshot = snapshotDirectory.resolve(FILE);
        String generated = JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(mapOf(routes)).replace("\r\n", "\n") + "\n";
        Files.createDirectories(generatedDirectory);
        Files.writeString(generatedDirectory.resolve(FILE), generated, StandardCharsets.UTF_8);

        if (update) {
            Files.createDirectories(snapshotDirectory);
            Files.writeString(snapshot, generated, StandardCharsets.UTF_8);
            fail("%s was rewritten because -D%s=true is set; review the diff, commit it and run "
                    + "again without the property", snapshot, UPDATE_PROPERTY);
        }
        if (!Files.exists(snapshot)) {
            fail("no approved snapshot at %s yet. Generated map, to review and commit:%n%s",
                    snapshot, generated);
        }
        List<String> approved = routeLines(parse(Files.readString(snapshot,
                StandardCharsets.UTF_8)));
        List<String> current = routeLines(mapOf(routes));
        List<String> differences = new ArrayList<>();
        current.stream().filter(line -> !approved.contains(line))
                .forEach(line -> differences.add("added: " + line));
        approved.stream().filter(line -> !current.contains(line))
                .forEach(line -> differences.add("removed: " + line));
        if (!differences.isEmpty()) {
            fail("the portal route map differs from its approved snapshot %s (%s). The generated "
                    + "map is in %s; if the change is intended, update the snapshot with -D%s=true "
                    + "and commit it", snapshot, String.join("; ", differences),
                    generatedDirectory.resolve(FILE), UPDATE_PROPERTY);
        }
    }

    private static Map<String, Object> mapOf(List<Route> routes) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("process", "portal");
        List<Map<String, String>> entries = new ArrayList<>();
        for (Route route : routes) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("method", route.method());
            entry.put("pattern", route.pattern());
            entries.add(entry);
        }
        map.put("routes", entries);
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<String> routeLines(Map<String, Object> map) {
        List<String> lines = new ArrayList<>();
        for (Object entry : (List<Object>) map.get("routes")) {
            Map<String, String> route = (Map<String, String>) entry;
            lines.add(route.get("method") + " " + route.get("pattern"));
        }
        return lines;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        return JSON.readValue(json, Map.class);
    }
}
