package com.confia.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the dated editorial notes that ADR-0025 introduced (scenarios BJ41, BJ72): the ADR file and
 * its index row exist, ADR-0017, {@code docs/03} section 6.1 and {@code docs/08} section 6.2 carry a
 * dated note, and none of their original lines was rewritten. The originals are embedded as
 * fixtures under {@code src/test/resources/documentation-notes}, copies of the files as they were
 * before the notes; every fixture line must still appear, in order, in the live document.
 */
class DocumentationNotesTest {

    private static final String ADR_0025 = "docs/adr/ADR-0025-purga-por-funciones-security-definer.md";
    private static final String ADR_0017 = "docs/adr/ADR-0017-tablas-tecnicas-y-tabla-raiz.md";
    private static final String DOCS_03 = "docs/03-seguridad.md";
    private static final String DOCS_08 = "docs/08-datos-privacidad-y-retencion.md";
    private static final String NOTE_MARKER = "**Nota editorial, 2026-10-29";
    private static final String ADR_NOTE_MARKER = "**Nota fechada, 2026-10-29";

    @Test
    void adr0025ExistsAsProposedAndIsIndexed() {
        List<String> adr = lines(repoRoot().resolve(ADR_0025));
        assertThat(adr).contains("- **Estado:** Propuesto");

        List<String> readme = lines(repoRoot().resolve("docs/adr/README.md"));
        assertThat(readme)
                .anySatisfy(row -> assertThat(row)
                        .contains("(ADR-0025-purga-por-funciones-security-definer.md)")
                        .contains("| Propuesto |"));
    }

    @Test
    void adr0017HasDatedNoteAndUnchangedOriginalBody() {
        List<String> live = lines(repoRoot().resolve(ADR_0017));
        assertThat(String.join("\n", live)).contains(ADR_NOTE_MARKER).contains("ADR-0025");
        assertOriginalLinesIntact(ADR_0017, "ADR-0017.original.md", live);
    }

    @Test
    void docs03Section61HasDatedNoteAndUnchangedOriginalBody() {
        List<String> section = section(lines(repoRoot().resolve(DOCS_03)), "### 6.1 ");
        assertThat(String.join("\n", section)).contains(NOTE_MARKER).contains("ADR-0025");
        assertOriginalLinesIntact(DOCS_03 + " section 6.1", "docs-03-section-6.1.original.md", section);
    }

    @Test
    void docs08Section62HasDatedNoteAndUnchangedOriginalBody() {
        List<String> section = section(lines(repoRoot().resolve(DOCS_08)), "### 6.2 ");
        assertThat(String.join("\n", section)).contains(NOTE_MARKER).contains("ADR-0025");
        assertOriginalLinesIntact(DOCS_08 + " section 6.2", "docs-08-section-6.2.original.md", section);
    }

    private static void assertOriginalLinesIntact(String label, String fixtureName, List<String> live) {
        List<String> original = fixture(fixtureName);
        int cursor = 0;
        for (int i = 0; i < original.size(); i++) {
            String expected = original.get(i);
            int found = live.subList(cursor, live.size()).indexOf(expected);
            assertThat(found)
                    .as("The original body of %s changed: original line %d is missing or out of order: \"%s\"",
                            label, i + 1, expected)
                    .isGreaterThanOrEqualTo(0);
            cursor += found + 1;
        }
    }

    /** Lines from the heading that starts with {@code headingPrefix} up to the next heading. */
    private static List<String> section(List<String> all, String headingPrefix) {
        List<String> result = new ArrayList<>();
        boolean inside = false;
        for (String line : all) {
            if (line.startsWith(headingPrefix)) {
                inside = true;
            } else if (inside && (line.startsWith("## ") || line.startsWith("### "))) {
                break;
            }
            if (inside) {
                result.add(line);
            }
        }
        assertThat(result).as("section %s", headingPrefix).isNotEmpty();
        return result;
    }

    private static List<String> fixture(String name) {
        return lines(Paths.get("src/test/resources/documentation-notes").toAbsolutePath().resolve(name));
    }

    private static List<String> lines(Path file) {
        assertThat(file).as("file %s", file).exists();
        try {
            return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("docs/adr"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find the repository root from " + Paths.get("").toAbsolutePath());
    }
}
