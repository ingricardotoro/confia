package com.confia.shared.web.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Specs/web-edge, "La lista de proxies de confianza es una propiedad por entorno, vacía por
 * defecto" (web-edge-foundations design.md, decision 12). The binding is exercised with Spring
 * Boot's own {@link Binder}, over the same sources the process uses, so what is proved is what
 * the process does when it starts: an absent property is an empty list, a range is accepted, a bad
 * entry stops the start with a message that names the property and its position, and the
 * environment variable reaches the property. The configuration files shipped with the repository
 * never set a list, and the servlet container never rewrites the remote address on its own.
 */
class WebEdgePropertiesTest {

    private static final String LIST = "confia.web.trusted-proxies";
    private static final List<String> PROFILES = List.of("application.yml",
            "application-local.yml", "application-preprod.yml", "application-migrate.yml");

    private static WebEdgeProperties bind(PropertySource<?>... sources) {
        return new Binder(ConfigurationPropertySources.from(List.of(sources)))
                .bindOrCreate("confia.web", WebEdgeProperties.class);
    }

    private static MapPropertySource properties(Map<String, Object> values) {
        return new MapPropertySource("test", values);
    }

    @Test
    void anAbsentPropertyIsAnEmptyListAndTheDefaultAgentLimit() {
        WebEdgeProperties bound = bind();

        assertThat(bound.trustedProxies()).isEmpty();
        assertThat(bound.userAgentMaxLength()).isEqualTo(512);
    }

    @Test
    void aRangeAndASingleAddressAreBoundAsWritten() {
        WebEdgeProperties bound = bind(properties(Map.of(LIST, "10.0.0.0/8,192.168.0.1,2001:db8::/32")));

        assertThat(bound.trustedProxies())
                .containsExactly("10.0.0.0/8", "192.168.0.1", "2001:db8::/32");
    }

    @Test
    void theEnvironmentVariableFeedsTheProperty() {
        // Spring Boot maps an environment variable only for a source with the name it gives the
        // real one: "systemEnvironment", optionally after a prefix.
        String name = "test-" + StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        SystemEnvironmentPropertySource environment = new SystemEnvironmentPropertySource(name,
                Map.of("CONFIA_WEB_TRUSTEDPROXIES", "10.0.0.1"));
        SystemEnvironmentPropertySource several = new SystemEnvironmentPropertySource(name,
                Map.of("CONFIA_WEB_TRUSTEDPROXIES", "10.0.0.1, 10.0.0.2"));

        assertThat(bind(environment).trustedProxies()).containsExactly("10.0.0.1");
        assertThat(bind(several).trustedProxies()).containsExactly("10.0.0.1", "10.0.0.2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.0/33", "no-es-una-ip"})
    void anInvalidEntryStopsTheStartAndNamesThePropertyAndItsPosition(String invalid) {
        MapPropertySource source = properties(Map.of(LIST + "[0]", "10.0.0.1", LIST + "[1]", invalid));

        assertThatThrownBy(() -> bind(source)).satisfies(failure ->
                assertThat(messagesOf(failure)).contains(LIST + "[1]").doesNotContain(LIST + "[0]")
                        .doesNotContain(invalid));
    }

    /**
     * A range written with host bits set ({@code 10.0.0.5/8}) would be widened in silence to
     * {@code 10.0.0.0/8}: a typo would trust sixteen million addresses. The start fails instead,
     * naming the position and never repeating the entry (the review of 2.3a, S-2).
     */
    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.5/8", "192.168.1.1/24", "10.0.0.1/0", "2001:db8::1/32",
            "2001:db8:0:0:0:0:0:1/127"})
    void aRangeWithHostBitsSetStopsTheStartAndNamesThePositionWithoutRepeatingIt(String entry) {
        MapPropertySource source = properties(Map.of(LIST + "[0]", "10.0.0.1", LIST + "[1]", entry));

        assertThatThrownBy(() -> bind(source)).satisfies(failure ->
                assertThat(messagesOf(failure)).contains(LIST + "[1]").doesNotContain(LIST + "[0]")
                        .doesNotContain(entry).doesNotContain(entry.substring(0, entry.indexOf('/')))
                        .contains("host bits"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.0/8", "10.0.0.5", "10.0.0.5/32", "0.0.0.0/0", "10.0.0.4/30",
            "2001:db8::/32", "2001:db8::1/128", "::/0"})
    void aRangeWhoseHostBitsAreAllZeroIsAccepted(String entry) {
        assertThat(bind(properties(Map.of(LIST, entry))).trustedProxies()).containsExactly(entry);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    void anAgentLimitThatIsNotAPositiveNumberStopsTheStartNamingTheProperty(String invalid) {
        MapPropertySource source = properties(Map.of("confia.web.user-agent-max-length", invalid));

        assertThatThrownBy(() -> bind(source)).satisfies(failure ->
                assertThat(messagesOf(failure)).contains("confia.web.user-agent-max-length"));
    }

    /** Every message of the cause chain, which is what a start failure prints. */
    private static String messagesOf(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
        }
        return String.join(" | ", messages);
    }

    @Test
    void noConfigurationFileOfTheRepositoryFixesAListOfTrustedProxies() throws IOException {
        for (String name : PROFILES) {
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load(name, new ClassPathResource(name))) {
                assertThat(((MapPropertySource) source).getPropertyNames())
                        .as(name).noneMatch(key -> key.startsWith(LIST));
            }
        }
    }

    /**
     * The profile list above only knows the files that exist today. This walks the repository, so
     * a new profile, an environment file or a compose file that fixes a list is found too. A line
     * counts when it is not a comment and its text, without dashes, underscores and dots, holds
     * {@code trustedproxies} in any case ({@code trusted-proxies}, {@code trustedProxies} or
     * {@code CONFIA_WEB_TRUSTEDPROXIES}). Tests and documentation may name it; they do not run.
     */
    @Test
    void noConfigurationFileAnywhereInTheRepositoryFixesAListOfTrustedProxies() throws IOException {
        Path root = repositoryRoot();
        List<Path> scanned = configurationFiles(root);

        assertThat(scanned).as("non-vacuous: the shipped defaults are among the files scanned")
                .anyMatch(path -> path.endsWith(Path.of("resources", "application.yml")));
        for (Path file : scanned) {
            assertThat(linesSettingTheList(Files.readAllLines(file)))
                    .as(root.relativize(file).toString()).isEmpty();
        }
    }

    @Test
    void theRepositoryScanFindsAFileThatSetsTheListInEveryFormAnEnvironmentUses() {
        assertThat(linesSettingTheList(List.of("confia:", "  web:", "    trusted-proxies: 10.0.0.1")))
                .hasSize(1);
        assertThat(linesSettingTheList(List.of("environment:", "  - CONFIA_WEB_TRUSTEDPROXIES=10.0.0.1")))
                .hasSize(1);
        assertThat(linesSettingTheList(List.of("confia.web.trustedProxies[0]=10.0.0.1"))).hasSize(1);
        assertThat(linesSettingTheList(List.of("# CONFIA_WEB_TRUSTEDPROXIES is set by the environment",
                "key: value # trusted-proxies stays empty", "other: 1"))).isEmpty();
    }

    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("pnpm-workspace.yaml"))) {
            directory = directory.getParent();
        }
        assertThat(directory).as("the repository root, found by pnpm-workspace.yaml").isNotNull();
        return directory;
    }

    private static List<Path> configurationFiles(Path root) throws IOException {
        Set<String> skipped = Set.of(".git", "node_modules", "target", "openspec", "docs", "test");
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> root.relativize(path).getParent() == null
                            || StreamSupport.stream(root.relativize(path).getParent().spliterator(), false)
                                    .noneMatch(part -> skipped.contains(part.toString())))
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".yml") || name.endsWith(".yaml")
                                || name.endsWith(".properties") || name.endsWith(".env")
                                || name.startsWith(".env") || name.startsWith("dockerfile")
                                || name.startsWith("compose") || name.startsWith("docker-compose");
                    })
                    .toList();
        }
    }

    private static List<String> linesSettingTheList(List<String> lines) {
        return lines.stream()
                .map(line -> line.stripLeading())
                .filter(line -> !line.startsWith("#"))
                .map(line -> line.contains(" #") ? line.substring(0, line.indexOf(" #")) : line)
                .filter(line -> line.toLowerCase(Locale.ROOT).replaceAll("[-_.]", "")
                        .contains("trustedproxies"))
                .toList();
    }

    @Test
    void theContainerNeverRewritesTheRemoteAddressFromAForwardedHeaderOnItsOwn() throws IOException {
        // Without this line Spring Boot switches the native strategy on when it detects a cloud
        // platform, and Tomcat then rewrites the remote address from X-Forwarded-For with its own
        // private ranges, which defeats the trusted-proxy list.
        PropertySource<?> defaults = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml")).get(0);

        assertThat(defaults.getProperty("server.forward-headers-strategy")).isEqualTo("none");
        assertThat(defaults.getProperty("confia.web.user-agent-max-length")).isEqualTo(512);
    }
}
