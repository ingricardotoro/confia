package com.confia.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-0018, mechanism (b), "Escáner de supresiones sin ADR": walks every source file under {@code
 * apps/api} and fails the build when it finds a suppression marker with no {@code ADR-NNNN}
 * citation next to it, a citation that names an ADR that does not exist under {@code docs/adr/},
 * a literal {@code failOnEmptyShould=false} anywhere, or a mismatch between the number of {@code
 * allowEmptyShould(} call sites and {@link EmptyShouldExceptionInventoryTest}'s inventory. This is
 * what turns the build-integrity requirement "ninguna regla se desactiva sin un ADR" into an
 * executable check instead of prose in a {@code package-info.java}.
 *
 * <p>verify-report.md W7: {@code .xml} joined the scanned extensions alongside {@code .java} and
 * {@code .properties} because the {@code pom.xml} files under {@code apps/api} are exactly where
 * every {@code maven-enforcer-plugin} rule and its possible future exclusions live; a scanner that
 * never looked at them left that surface covered only by the prose comment at {@code
 * apps/api/pom.xml}'s {@code enforce-build-integrity} execution, which is precisely the kind of
 * unmechanized "regla" ADR-0018 exists to replace.
 *
 * <p>The marker catalog starts with {@code allowEmptyShould(}, {@code failOnEmptyShould} and
 * ArchUnit's {@code @ArchIgnore} exclusion annotation (ADR-0018, section 3.b) and grows with every
 * tool that gains its own exclusion mechanism (JaCoCo and PIT in change 2, dependency-cruiser and
 * ESLint in change 3).
 *
 * <p>This file excludes itself from the walk: it necessarily spells out the marker patterns it
 * looks for, which is cataloging a marker, not suppressing a rule.
 */
class SuppressionCitesAdrTest {

    private static final String SELF_FILE_NAME = "SuppressionCitesAdrTest.java";
    private static final Set<String> SCANNED_EXTENSIONS = Set.of(".java", ".properties", ".xml");
    private static final int ADJACENT_LINES = 4;

    private static final Pattern ALLOW_EMPTY_SHOULD_CALL =
            Pattern.compile("\\.allowEmptyShould\\((?:true|false)\\)");
    private static final Pattern ARCH_IGNORE_ANNOTATION = Pattern.compile("@ArchIgnore\\b");
    private static final Pattern FAIL_ON_EMPTY_SHOULD_FALSE =
            Pattern.compile("failOnEmptyShould\\s*=\\s*false");
    private static final Pattern ADR_CITATION = Pattern.compile("ADR-(\\d{4})");

    @Test
    void everySuppressionCitesAnExistingAdrAndNoGlobalEscapeHatchRemains() throws IOException {
        Path repoRoot = findAncestorContaining("docs/adr");
        Path adrDirectory = repoRoot.resolve("docs/adr");
        List<Path> files = scannableFiles(repoRoot.resolve("apps/api"));

        List<String> problems = new ArrayList<>();
        int allowEmptyShouldOccurrences = 0;

        for (Path file : files) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (FAIL_ON_EMPTY_SHOULD_FALSE.matcher(line).find()) {
                    problems.add(file + ":" + (i + 1) + " sets failOnEmptyShould=false, which "
                            + "ADR-0018 forbids in any file; use per-rule allowEmptyShould(true) "
                            + "instead, cited to the ADR that authorizes it.");
                }
                if (ALLOW_EMPTY_SHOULD_CALL.matcher(line).find()) {
                    allowEmptyShouldOccurrences++;
                    requireAdjacentAdrCitation(lines, i, file, adrDirectory, problems);
                }
                if (ARCH_IGNORE_ANNOTATION.matcher(line).find()) {
                    requireAdjacentAdrCitation(lines, i, file, adrDirectory, problems);
                }
            }
        }

        assertThat(problems).as("undocumented or invalid suppressions found").isEmpty();
        assertThat(allowEmptyShouldOccurrences)
                .as("allowEmptyShould( call sites must match EmptyShouldExceptionInventoryTest's "
                        + "inventory exactly (ADR-0018): every exception is declared, and every "
                        + "declared exception corresponds to a real allowEmptyShould(true) call")
                .isEqualTo(EmptyShouldExceptionInventoryTest.EXCEPTIONS.size());
    }

    private static void requireAdjacentAdrCitation(List<String> lines, int markerLine, Path file,
            Path adrDirectory, List<String> problems) {
        int from = Math.max(0, markerLine - ADJACENT_LINES);
        int to = Math.min(lines.size() - 1, markerLine + ADJACENT_LINES);
        for (int i = from; i <= to; i++) {
            Matcher citation = ADR_CITATION.matcher(lines.get(i));
            if (citation.find()) {
                String adrNumber = citation.group(1);
                if (!adrFileExists(adrDirectory, adrNumber)) {
                    problems.add(file + ":" + (markerLine + 1) + " cites ADR-" + adrNumber
                            + ", which does not exist under docs/adr/.");
                }
                return;
            }
        }
        problems.add(file + ":" + (markerLine + 1) + " suppresses a rule with no ADR-NNNN "
                + "citation within " + ADJACENT_LINES + " lines.");
    }

    private static boolean adrFileExists(Path adrDirectory, String adrNumber) {
        String prefix = "ADR-" + adrNumber;
        try (Stream<Path> adrFiles = Files.list(adrDirectory)) {
            return adrFiles.anyMatch(candidate -> candidate.getFileName().toString()
                    .startsWith(prefix));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> scannableFiles(Path appsApiDirectory) throws IOException {
        try (Stream<Path> walk = Files.walk(appsApiDirectory)) {
            return walk.filter(SuppressionCitesAdrTest::isScannable).collect(Collectors.toList());
        }
    }

    private static boolean isScannable(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String name = path.getFileName().toString();
        if (name.equals(SELF_FILE_NAME)) {
            return false;
        }
        String normalized = path.toString().replace('\\', '/');
        if (normalized.contains("/target/")) {
            return false;
        }
        return SCANNED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    /**
     * Walks up from wherever Surefire starts this module's working directory until it finds a
     * directory that directly contains {@code relativeMarker} (here {@code "docs/adr"}), so this
     * test does not depend on which module invoked it or how deep its basedir sits under the
     * repository root.
     */
    private static Path findAncestorContaining(String relativeMarker) {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve(relativeMarker))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find an ancestor containing " + relativeMarker
                + " starting from " + Paths.get("").toAbsolutePath());
    }
}
