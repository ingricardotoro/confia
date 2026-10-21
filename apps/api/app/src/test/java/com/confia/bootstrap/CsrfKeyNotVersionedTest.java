package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Specs/web-edge, scenario WE61, extending the secret scan of BI29 (session-endpoints-cookie-and-csrf
 * tasks.md, 1.1): no tracked configuration, script or source file of the repository gives a value to
 * {@code confia.security.csrf.key} or {@code confia.security.csrf.previous-key}, in the dotted form,
 * in the environment-variable form or as a YAML key under {@code csrf}. Tests generate their keys at
 * run time; documentation and {@code openspec/} may name the properties and are not scanned.
 */
class CsrfKeyNotVersionedTest {

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "node_modules", "target",
            "openspec", "docs", ".claude", ".atl", ".kilo");
    private static final Set<String> SCANNED_SUFFIXES = Set.of(".yml", ".yaml", ".properties",
            ".env", ".java", ".sh", ".json", ".xml");

    /** A line that gives the property a value: {@code name=value} or {@code name: value}. */
    private static final Pattern ASSIGNMENT = Pattern.compile("(?i)(confia\\.security\\.csrf\\."
            + "(previous-)?key|CONFIA_SECURITY_CSRF_(PREVIOUS_?)?KEY)[\"']?\\s*[=:]\\s*[\"']?[^\\s\"'#]");

    /** A YAML line {@code key: value} or {@code previous-key: value}, whose parent is {@code csrf}. */
    private static final Pattern YAML_KEY = Pattern.compile("^\\s+(previous-)?key:\\s*[^\\s#]");
    private static final Pattern YAML_CSRF = Pattern.compile("^\\s*csrf:\\s*(#.*)?$");

    @Test
    void noFileOfTheRepositoryGivesAValueToACsrfKey() throws IOException {
        Path root = repositoryRoot();
        List<Path> scanned = scannedFiles(root);

        assertThat(scanned).as("non-vacuous: the shipped defaults are among the files scanned")
                .anyMatch(path -> path.endsWith(Path.of("resources", "application.yml")));
        assertThat(scanned).as("non-vacuous: this tree's test sources are scanned too")
                .anyMatch(path -> path.getFileName().toString().equals("TestProcessArguments.java"));
        for (Path file : scanned) {
            assertThat(linesGivingAValue(Files.readAllLines(file)))
                    .as(root.relativize(file).toString()).isEmpty();
        }
    }

    @Test
    void theScanFindsTheKeyInEveryFormAnEnvironmentUses() {
        String dotted = "confia.security.csrf" + ".key";
        String previous = "confia.security.csrf" + ".previous-key";
        String variable = "CONFIA_SECURITY_CSRF" + "_KEY";
        String previousVariable = "CONFIA_SECURITY_CSRF" + "_PREVIOUSKEY";

        assertThat(linesGivingAValue(List.of(dotted + "=abc"))).hasSize(1);
        assertThat(linesGivingAValue(List.of(dotted + ": abc"))).hasSize(1);
        assertThat(linesGivingAValue(List.of(previous + "=abc"))).hasSize(1);
        assertThat(linesGivingAValue(List.of("  - " + variable + "=abc"))).hasSize(1);
        assertThat(linesGivingAValue(List.of(previousVariable + ": abc"))).hasSize(1);
        assertThat(linesGivingAValue(List.of("confia:", "  security:", "    csrf:", "      key: abc")))
                .hasSize(1);
        assertThat(linesGivingAValue(List.of("    csrf:  # the CSRF block", "      previous-key: abc")))
                .hasSize(1);
    }

    @Test
    void theScanIgnoresAMentionThatGivesNoValue() {
        String dotted = "confia.security.csrf" + ".key";

        assertThat(linesGivingAValue(List.of("static final String P = \"" + dotted + "\";",
                "# " + dotted + " comes from the environment", dotted + "=", "csrf:",
                "  other: value", "private static final String KEY = \"unrelated\";"))).isEmpty();
    }

    private static List<String> linesGivingAValue(List<String> lines) {
        List<String> found = new java.util.ArrayList<>();
        int csrfIndent = -1;
        for (String line : lines) {
            String stripped = line.stripLeading();
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("//")
                    || stripped.startsWith("*")) {
                continue;
            }
            int indent = line.length() - stripped.length();
            if (csrfIndent >= 0 && indent <= csrfIndent) {
                csrfIndent = -1;
            }
            if (YAML_CSRF.matcher(line).matches()) {
                csrfIndent = indent;
            } else if (csrfIndent >= 0 && YAML_KEY.matcher(line).find()) {
                found.add(line);
            }
            if (ASSIGNMENT.matcher(line).find()) {
                found.add(line);
            }
        }
        return found;
    }

    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("pnpm-workspace.yaml"))) {
            directory = directory.getParent();
        }
        assertThat(directory).as("the repository root, found by pnpm-workspace.yaml").isNotNull();
        return directory;
    }

    private static List<Path> scannedFiles(Path root) throws IOException {
        List<Path> files = new java.util.ArrayList<>();
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                    java.nio.file.attribute.BasicFileAttributes attributes) {
                // Skipped directories are not entered at all: node_modules is huge.
                return !dir.equals(root) && SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())
                        ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                        : java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attributes) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean scanned = SCANNED_SUFFIXES.stream().anyMatch(name::endsWith)
                        || name.startsWith(".env") || name.startsWith("dockerfile")
                        || name.startsWith("compose") || name.startsWith("docker-compose");
                if (scanned && !name.equals("csrfkeynotversionedtest.java")) {
                    files.add(file);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return files;
    }
}
