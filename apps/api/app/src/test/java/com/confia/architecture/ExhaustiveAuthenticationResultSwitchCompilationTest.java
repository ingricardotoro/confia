package com.confia.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The compilation-failure fixture design.md decision 6 requires (column-encryption-and-mfa-totp,
 * §9 sonda S3; specs/identity/spec.md, requirement "Exhaustividad forzada por el compilador sobre
 * los cuatro desenlaces de {@code AuthenticationResult}"). A normal test can only assert on the
 * behavior of something that already compiled — it cannot assert that something fails to compile.
 * This class compiles two fixed fixtures in memory with {@link javax.tools.JavaCompiler}, against
 * the real, already-compiled {@link com.confia.identity.domain.AuthenticationResult} on this JVM's
 * own classpath: a real {@code switch} over the real sealed type, not an imitation.
 *
 * <p>Both fixtures live as {@code .txt} resources under {@code architecture-fixtures/}, never as
 * real {@code .java} files in this tree — {@code non-exhaustive-switch.java.txt} would otherwise
 * break {@code ./mvnw verify} for everyone, not only for this one test.
 *
 * <p><b>Sonda S3 (design.md §9), resolved.</b> The task this class implements originally
 * prescribed asserting that the non-exhaustive diagnostic contains the word "exhaustive". It does
 * not, on {@code javac} 25.0.3: the literal messages are "the switch expression does not cover all
 * possible input values" (switch-as-expression, this fixture's own form) and "the switch statement
 * does not cover all possible input values" (switch-as-statement). {@link
 * #NON_EXHAUSTIVE_DIAGNOSTIC_FRAGMENT} asserts on the substring stable between the two forms
 * instead — adjusted to what {@code javac} actually produces, never relaxed to a bare "compilation
 * failed", exactly as the task's own instruction required. {@link Locale#ROOT} guards the
 * substring match against a differently localized JDK distribution in continuous integration,
 * even though Temurin 25 was confirmed, in this same probe, to never localize compiler diagnostics
 * regardless of the JVM's own default locale.
 */
class ExhaustiveAuthenticationResultSwitchCompilationTest {

    private static final String FIXTURE_RESOURCE_DIRECTORY = "architecture-fixtures/";
    private static final String NON_EXHAUSTIVE_DIAGNOSTIC_FRAGMENT =
            "does not cover all possible input values";

    @Test
    void aSwitchMissingOneOutcomeFailsToCompile(@TempDir Path outputDirectory) {
        CompilationResult result = compile(readFixture("non-exhaustive-switch.java.txt"),
                "com.confia.architecture.fixture.exhaustiveswitch.NonExhaustiveSwitchFixture",
                outputDirectory);

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics())
                .as("javac 25 must reject the fixture with SecondFactorRequired's branch removed, "
                        + "on the substring stable between its switch-as-expression and "
                        + "switch-as-statement diagnostics (design.md §9, sonda S3)")
                .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                        && d.getMessage(Locale.ROOT).toLowerCase(Locale.ROOT)
                                .contains(NON_EXHAUSTIVE_DIAGNOSTIC_FRAGMENT));
    }

    /**
     * The control positive design.md decision 6 requires in the same task as the negative half
     * above: without it, a compilation harness broken in a way that always reported failure would
     * pass {@link #aSwitchMissingOneOutcomeFailsToCompile} just as well, having never actually
     * looked at anything. Compiled with {@code -Xlint:all}, matching sonda S3's own finding that
     * the real four-branch switch compiles clean, without a single warning.
     */
    @Test
    void theRealFourBranchSwitchCompilesCleanly(@TempDir Path outputDirectory) {
        CompilationResult result = compile(readFixture("exhaustive-switch.java.txt"),
                "com.confia.architecture.fixture.exhaustiveswitch.ExhaustiveSwitchFixture",
                outputDirectory, "-Xlint:all");

        assertThat(result.success()).isTrue();
        assertThat(result.diagnostics())
                .as("sonda S3 found the real four-branch switch compiles under -Xlint:all without "
                        + "a single warning, not merely without an error")
                .isEmpty();
    }

    private static String readFixture(String resourceName) {
        try (var stream = ExhaustiveAuthenticationResultSwitchCompilationTest.class
                .getClassLoader()
                .getResourceAsStream(FIXTURE_RESOURCE_DIRECTORY + resourceName)) {
            if (stream == null) {
                throw new IllegalStateException("fixture resource not found: " + resourceName);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Compiles {@code source} in memory, against this JVM's own classpath (which already carries
     * the real, compiled {@link com.confia.identity.domain.AuthenticationResult}), with class
     * output redirected to a JUnit-managed temporary directory so the in-memory {@link
     * JavaFileObject}'s synthetic {@code string:///} URI never needs to resolve a real output
     * location on disk.
     */
    private static CompilationResult compile(String source, String fullyQualifiedClassName,
            Path outputDirectory, String... extraOptions) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        JavaFileObject sourceObject = new InMemoryJavaSource(fullyQualifiedClassName, source);

        List<String> options = new java.util.ArrayList<>(List.of("-classpath",
                System.getProperty("java.class.path"), "-d", outputDirectory.toString()));
        options.addAll(List.of(extraOptions));

        boolean success = compiler
                .getTask(null, fileManager, diagnostics, options, null, List.of(sourceObject))
                .call();
        return new CompilationResult(success, diagnostics.getDiagnostics());
    }

    private record CompilationResult(boolean success,
            List<Diagnostic<? extends JavaFileObject>> diagnostics) {
    }

    /** A source file that exists only in memory: never written to, or read from, disk. */
    private static final class InMemoryJavaSource extends SimpleJavaFileObject {
        private final String source;

        InMemoryJavaSource(String fullyQualifiedClassName, String source) {
            super(URI.create("string:///" + fullyQualifiedClassName.replace('.', '/')
                    + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
