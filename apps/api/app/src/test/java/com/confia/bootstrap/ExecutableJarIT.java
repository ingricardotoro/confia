package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The executable jar of the build (scenarios CR01, CR02, BI119 and BI120 of
 * dockerfiles-and-local-compose, design.md decision 1). It runs in Failsafe, after {@code package}:
 * the jar is the artifact the repackage execution of the {@code app} module leaves at the fixed
 * path {@code target/confia-api.jar}, produced by the same {@code verify} that ran every other rule
 * of the build.
 *
 * <p>The launcher is started as a real child process, with a fixed argument vector and no shell, so
 * what is proven is the artifact and not the test class path.
 */
class ExecutableJarIT {

    private static final String JAR_NAME = "confia-api.jar";
    private static final long PROCESS_TIMEOUT_SECONDS = 90;

    /** Key names whose value, if present in a packaged configuration file, must be a placeholder. */
    private static final Pattern SECRET_KEY =
            Pattern.compile("(?i)(password|secret|private-key|master-key|pepper|credential)");

    /** A value that carries no secret: empty, or a property placeholder resolved at run time. */
    private static final Pattern PLACEHOLDER_VALUE =
            Pattern.compile("^\\s*(\"\"|''|\\$\\{[^}]*\\})?\\s*(#.*)?$");

    @Test
    void theBuildLeavesTheExecutableJarAtTheFixedPath() {
        assertThat(jarPath()).as("the repackage execution must leave %s", JAR_NAME).isRegularFile();
    }

    @Test
    void theJarExitsWithOneWhenTheProfileIsInvalid() throws Exception {
        Path output = Files.createTempFile(moduleBasedir().resolve("target"), "invalid-profile", ".log");
        ProcessBuilder builder = new ProcessBuilder(javaExecutable(), "-jar", jarPath().toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        builder.environment().put(AppProfile.ENV_VARIABLE, "not-a-profile");
        Process launched = builder.start();

        boolean finished = launched.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            launched.destroyForcibly();
        }
        assertThat(finished).as("the launcher must terminate by itself on an invalid profile")
                .isTrue();
        assertThat(launched.exitValue()).isEqualTo(1);
        // Non-vacuous: a missing jar also exits with 1, so the launcher's own message must be there.
        assertThat(Files.readString(output, StandardCharsets.UTF_8))
                .contains("Unknown or missing " + AppProfile.ENV_VARIABLE);
    }

    @Test
    void theJarAppliesTheMigrationsAsTheOwnerAndExitsWithZero() throws Exception {
        try (ScratchDatabase database = ScratchDatabase.ownedByTheRealOwner()) {
            Path output = Files.createTempFile(moduleBasedir().resolve("target"), "migrate", ".log");
            ProcessBuilder builder = new ProcessBuilder(javaExecutable(), "-jar",
                    jarPath().toString())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            builder.environment().put(AppProfile.ENV_VARIABLE, "migrate");
            builder.environment().put("SPRING_DATASOURCE_URL", database.jdbcUrl());
            builder.environment().put("SPRING_DATASOURCE_USERNAME", database.ownerRole());
            builder.environment().put("SPRING_DATASOURCE_PASSWORD", database.ownerPassword());
            Process launched = builder.start();

            boolean finished = launched.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                launched.destroyForcibly();
            }
            String log = Files.readString(output, StandardCharsets.UTF_8);
            assertThat(finished).as("migrate must end by itself").isTrue();
            assertThat(launched.exitValue()).as(log).isZero();
            assertThat(log).doesNotContain(database.ownerPassword());
            try (java.sql.Connection connection = database.connectAsOwner();
                    java.sql.Statement statement = connection.createStatement();
                    java.sql.ResultSet result = statement.executeQuery(
                            "SELECT count(*) FROM flyway_schema_history WHERE success")) {
                result.next();
                assertThat(result.getInt(1)).as("non-vacuous: migrations applied").isPositive();
            }
        }
    }

    @Test
    void theJarEntriesHoldNoTestClassesNoTestFixturesAndNoSecretValues() throws IOException {
        List<String> names = new ArrayList<>();
        List<String> secretLines = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath().toFile())) {
            for (JarEntry entry : java.util.Collections.list(jar.entries())) {
                names.add(entry.getName());
                if (isPackagedConfiguration(entry.getName())) {
                    String content = new String(jar.getInputStream(entry).readAllBytes(),
                            StandardCharsets.UTF_8);
                    secretLines.addAll(linesCarryingASecretValue(entry.getName(), content));
                }
            }
        }

        assertThat(names).as("non-vacuous: the jar holds the application classes")
                .anyMatch(name -> name.equals("BOOT-INF/classes/com/confia/bootstrap/ConfiaApplication.class"));
        assertThat(names).as("no test class")
                .noneMatch(name -> name.endsWith("Test.class") || name.endsWith("IT.class")
                        || name.contains("/com/confia/support/")
                        || name.contains("/architecture/fixture/"));
        assertThat(names).as("no test resource directory")
                .noneMatch(name -> name.startsWith("BOOT-INF/classes/db/testing/")
                        || name.startsWith("BOOT-INF/classes/db/codegen/")
                        || name.startsWith("BOOT-INF/classes/db/scheduler/")
                        || name.startsWith("BOOT-INF/classes/db/broken/"));
        assertThat(names).as("non-vacuous: the migrations are packaged")
                .anyMatch(name -> name.startsWith("BOOT-INF/classes/db/migration/V1__"));
        assertThat(secretLines).as("configuration lines with a secret-like key and a literal value")
                .isEmpty();
    }

    @Test
    void theKernelModuleProducesNoExecutableJar() throws IOException {
        Path kernelTarget = moduleBasedir().resolveSibling("kernel").resolve("target");
        List<Path> jars;
        try (Stream<Path> files = Files.list(kernelTarget)) {
            jars = files.filter(file -> file.getFileName().toString().endsWith(".jar")).toList();
        }

        assertThat(jars).as("non-vacuous: the kernel library jar exists").isNotEmpty();
        for (Path jar : jars) {
            try (JarFile file = new JarFile(jar.toFile())) {
                assertThat(file.getManifest().getMainAttributes().getValue("Start-Class"))
                        .as("%s must stay a plain library", jar.getFileName()).isNull();
                assertThat(file.getEntry("BOOT-INF/classes/")).isNull();
            }
        }
    }

    /** Whether {@code name} is an application configuration file packaged in the jar. */
    private static boolean isPackagedConfiguration(String name) {
        return name.startsWith("BOOT-INF/classes/application")
                && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties"));
    }

    /**
     * The lines of one configuration file whose key looks like a secret and whose value is a literal.
     * Only the key and the value shape are reported, never the value itself.
     */
    static List<String> linesCarryingASecretValue(String file, String content) {
        List<String> found = new ArrayList<>();
        Pattern keyValue = Pattern.compile("^\\s*([A-Za-z0-9_.\\-]+)\\s*[:=](.*)$");
        int number = 0;
        for (String line : content.split("\\R", -1)) {
            number++;
            if (line.stripLeading().startsWith("#")) {
                continue;
            }
            Matcher matcher = keyValue.matcher(line);
            if (matcher.matches() && SECRET_KEY.matcher(matcher.group(1)).find()
                    && !PLACEHOLDER_VALUE.matcher(matcher.group(2)).matches()) {
                found.add(file + ":" + number + " key=" + matcher.group(1));
            }
        }
        return found;
    }

    private static Path moduleBasedir() {
        return Paths.get(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
    }

    private static Path jarPath() {
        return moduleBasedir().resolve("target").resolve(JAR_NAME);
    }

    private static String javaExecutable() {
        return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }
}
