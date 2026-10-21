package com.confia.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Specs/build-integrity, scenarios BI43 and BI44 (session-endpoints-cookie-and-csrf design.md,
 * decision 17): no production source assembles the detail JSON of an audit entry by hand. The
 * documents are built with {@code com.confia.shared.audit.AuditJson}, so a string literal that
 * opens a JSON object with a quoted member ({@code "{\""}) or a text block that holds one is a
 * violation, except in {@code com.confia.shared.security.token} (the claims and the header of a
 * signed token are not audit details) and in {@code AuditJson} itself.
 *
 * <p>A bytecode rule cannot see this: javac folds a concatenation into an {@code invokedynamic}
 * recipe, so the rule reads the source. Both halves of the repository's two-half convention
 * (ADR-0018): the production half has no empty-set exception and proves it looked at the eight
 * migrated classes; the fixture half must reject the three permanent bad fixtures and leave the
 * token fixture alone. Comments are ignored, so documentation may quote a document.
 */
class AuditJsonOnlySourceTest {

    private static final String TOKEN_PACKAGE = "com.confia.shared.security.token";
    private static final String AUDIT_JSON_CLASS = "com.confia.shared.audit.AuditJson";
    private static final Path PRODUCTION_ROOT = Path.of("src", "main", "java");
    private static final Path FIXTURE_ROOT = Path.of("src", "test", "java", "com", "confia",
            "architecture", "fixture", "auditjson");

    private static final List<String> MIGRATED_CLASSES = List.of(
            "com.confia.identity.application.AuthenticateWithPassword",
            "com.confia.identity.application.ConsumeRecoveryCode",
            "com.confia.identity.application.IssueSession",
            "com.confia.identity.application.VerifyTotpCode",
            "com.confia.identity.application.RequestPasswordReset",
            "com.confia.identity.application.IssuePasswordResetToken",
            "com.confia.identity.application.ResetPasswordWithToken",
            "com.confia.identity.application.RotateRefreshToken");

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TEXT_BLOCK_OBJECT = Pattern.compile("\\{\\s*\"");

    /** A literal that opens an audit document: the source text {@code {\"} inside a string. */
    private static final String ESCAPED_OPENING = "{\\\"";

    private record Source(String className, Path file, String text) {
    }

    private record Violation(String className, int line) {
        @Override
        public String toString() {
            return className + ":" + line;
        }
    }

    private record Literal(String content, boolean textBlock, int line) {
    }

    // --- The two halves --------------------------------------------------------------------

    @Test
    void noProductionSourceBuildsAnAuditDocumentByHand() throws IOException {
        List<Source> sources = sourcesUnder(PRODUCTION_ROOT);

        assertThat(sources.stream().map(Source::className))
                .as("non-vacuous: the eight migrated classes are evaluated")
                .containsAll(MIGRATED_CLASSES);
        assertThat(sources.stream().map(Source::className))
                .as("non-vacuous: the exempt classes are evaluated too, and exempted by rule")
                .contains(AUDIT_JSON_CLASS, TOKEN_PACKAGE + ".SigningKeyRing");
        assertThat(violationsOf(sources))
                .as("sites that still assemble an audit document by hand")
                .isEmpty();
    }

    @Test
    void theTokenPackageReallyAssemblesItsJsonByHand() throws IOException {
        List<Source> tokenSources = sourcesUnder(PRODUCTION_ROOT).stream()
                .filter(source -> source.className().startsWith(TOKEN_PACKAGE + "."))
                .toList();

        assertThat(tokenSources.stream()
                .anyMatch(source -> !literalsOf(source.text()).stream()
                        .filter(literal -> isOpening(literal))
                        .toList().isEmpty()))
                .as("the exemption is real: a class of the token package holds such a literal")
                .isTrue();
    }

    @Test
    void rejectsTheThreePermanentBadFixturesAndLeavesTheTokenFixtureAlone() throws IOException {
        List<Source> fixtures = sourcesUnder(FIXTURE_ROOT);

        assertThat(fixtures.stream().map(Source::className)).contains(
                "com.confia.architecture.fixture.auditjson.BadConcatenatedAuditJson",
                "com.confia.architecture.fixture.auditjson.BadFormattedAuditJson",
                "com.confia.architecture.fixture.auditjson.BadTextBlockAuditJson",
                TOKEN_PACKAGE + ".GoodTokenClaimsJson");
        assertThat(violationsOf(fixtures).stream().map(Violation::className))
                .containsExactlyInAnyOrder(
                        "com.confia.architecture.fixture.auditjson.BadConcatenatedAuditJson",
                        "com.confia.architecture.fixture.auditjson.BadFormattedAuditJson",
                        "com.confia.architecture.fixture.auditjson.BadTextBlockAuditJson");
    }

    // --- The reading of a source -----------------------------------------------------------

    @Test
    void aQuotedDocumentInsideAnyKindOfCommentIsNotAViolation() {
        String source = """
                package com.confia.sample;

                // "{\\"line\\":1}" quoted in a line comment
                /* "{\\"block\\":1}" quoted in a block comment */
                /** "{\\"javadoc\\":1}" quoted in documentation */
                class Sample {
                    String text = "plain";
                }
                """;

        assertThat(violationsOf(List.of(sourceOf("com.confia.sample.Sample", source)))).isEmpty();
    }

    @Test
    void aCommentMarkerInsideAStringDoesNotHideTheRestOfTheLine() {
        String source = "package p;\nclass A { String a = \"http://x\" + \"{\\\"k\\\":\" + 1; }\n";

        assertThat(violationsOf(List.of(sourceOf("p.A", source)))).hasSize(1);
    }

    @Test
    void anEscapedQuoteDoesNotEndTheLiteralEarly() {
        String source = "package p;\nclass A { String a = \"say \\\" then {\\\"k\\\":1}\"; }\n";

        assertThat(violationsOf(List.of(sourceOf("p.A", source)))).hasSize(1);
    }

    @Test
    void aBraceWithoutAQuotedMemberIsNotAViolation() {
        String source = "package p;\nclass A { String a = \"{}\"; String b = \"{\" + 1 + \"}\";"
                + " String c = \"use {} here\"; char d = '{'; char e = '\"'; }\n";

        assertThat(violationsOf(List.of(sourceOf("p.A", source)))).isEmpty();
    }

    @Test
    void aTextBlockWithoutAnObjectIsNotAViolation() {
        String source = "package p;\nclass A { String sql = \"\"\"\n select 1\n \"\"\"; }\n";

        assertThat(violationsOf(List.of(sourceOf("p.A", source)))).isEmpty();
    }

    @Test
    void aViolationNamesTheClassAndTheLineOfTheLiteral() {
        String source = "package p;\n\nclass A {\n  String a = 1\n      + \"{\\\"k\\\":\" + 2;\n}\n";

        assertThat(violationsOf(List.of(sourceOf("p.A", source))))
                .extracting(Violation::toString).containsExactly("p.A:5");
    }

    @Test
    void theAuditJsonClassAndTheTokenPackageAreExemptByTheirDeclaredPackage() {
        String literal = "class X { String a = \"{\\\"k\\\":1}\"; }\n";

        assertThat(violationsOf(List.of(
                sourceOf(AUDIT_JSON_CLASS, "package com.confia.shared.audit;\n" + literal),
                sourceOf(TOKEN_PACKAGE + ".X", "package " + TOKEN_PACKAGE + ";\n" + literal),
                sourceOf(TOKEN_PACKAGE + ".deeper.X",
                        "package " + TOKEN_PACKAGE + ".deeper;\n" + literal))))
                .isEmpty();
        assertThat(violationsOf(List.of(
                sourceOf("com.confia.shared.audit.Other", "package com.confia.shared.audit;\n" + literal))))
                .hasSize(1);
        assertThat(violationsOf(List.of(
                sourceOf("com.confia.shared.security.tokenizer.X",
                        "package com.confia.shared.security.tokenizer;\n" + literal))))
                .as("a sibling package that only shares the prefix is not exempt")
                .hasSize(1);
    }

    // --- The rule itself -------------------------------------------------------------------

    private static boolean isOpening(Literal literal) {
        return literal.textBlock()
                ? TEXT_BLOCK_OBJECT.matcher(literal.content()).find()
                : literal.content().contains(ESCAPED_OPENING);
    }

    private static boolean isExempt(Source source) {
        return source.className().equals(AUDIT_JSON_CLASS)
                || packageOf(source.className()).equals(TOKEN_PACKAGE)
                || packageOf(source.className()).startsWith(TOKEN_PACKAGE + ".")
                || declaredPackage(source.text()).equals(TOKEN_PACKAGE)
                || declaredPackage(source.text()).startsWith(TOKEN_PACKAGE + ".");
    }

    private static List<Violation> violationsOf(List<Source> sources) {
        List<Violation> violations = new ArrayList<>();
        for (Source source : sources) {
            if (isExempt(source)) {
                continue;
            }
            for (Literal literal : literalsOf(source.text())) {
                if (isOpening(literal)) {
                    violations.add(new Violation(source.className(), literal.line()));
                }
            }
        }
        return violations;
    }

    // --- Reading sources -------------------------------------------------------------------

    private static List<Source> sourcesUnder(Path root) throws IOException {
        assertThat(root).as("the source folder, relative to the module").isDirectory();
        List<Source> sources = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                String simpleName = file.getFileName().toString().replaceFirst("\\.java$", "");
                String declared = declaredPackage(text);
                sources.add(new Source(declared.isEmpty() ? simpleName : declared + "." + simpleName,
                        file, text));
            }
        }
        return sources;
    }

    private static Source sourceOf(String className, String text) {
        return new Source(className, Path.of(className), text);
    }

    private static String declaredPackage(String text) {
        Matcher matcher = PACKAGE.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    /**
     * The string literals and text blocks of a source, comments and character literals left out.
     * {@code content} is the raw source text between the quotes (an escaped quote stays escaped).
     */
    private static List<Literal> literalsOf(String text) {
        List<Literal> literals = new ArrayList<>();
        int line = 1;
        int i = 0;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (text.startsWith("//", i)) {
                while (i < length && text.charAt(i) != '\n') {
                    i++;
                }
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                int stop = end < 0 ? length : end + 2;
                line += count(text, i, stop);
                i = stop;
            } else if (text.startsWith("\"\"\"", i)) {
                int start = i + 3;
                int j = start;
                while (j < length && !text.startsWith("\"\"\"", j)) {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                int stop = Math.min(j, length);
                literals.add(new Literal(text.substring(start, stop), true, line));
                line += count(text, i, Math.min(stop + 3, length));
                i = Math.min(stop + 3, length);
            } else if (c == '"') {
                int j = i + 1;
                while (j < length && text.charAt(j) != '"' && text.charAt(j) != '\n') {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                int stop = Math.min(j, length);
                literals.add(new Literal(text.substring(i + 1, stop), false, line));
                i = Math.min(stop + 1, length);
            } else if (c == '\'') {
                int j = i + 1;
                while (j < length && text.charAt(j) != '\'' && text.charAt(j) != '\n') {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                i = Math.min(j + 1, length);
            } else {
                i++;
            }
        }
        return literals;
    }

    private static int count(String text, int from, int to) {
        int lines = 0;
        for (int i = from; i < Math.min(to, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
