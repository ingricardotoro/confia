package com.confia.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * The compilation-failure fixture of scenario AZ30 (rbac-permission-matrix-and-audit-integration
 * design.md D13): the permission of an entry of the closed list is a value of the catalog, so an entry
 * written with a text instead cannot be compiled, and a list with a permission outside the catalog
 * cannot exist at run time. A normal test cannot assert that something fails to compile, so this one
 * compiles two sources held in memory against the real, already compiled {@code PermissionedEndpoint}.
 * The expected error is the one of incompatible types, not the one of a missing symbol: a fixture that
 * failed for a typo would prove nothing. The twin with a value of the catalog compiles clean, so a
 * harness that always reported failure would not pass the first test.
 */
class PermissionedEndpointTextPermissionCompilationTest {

    private static final String TEXT_FIXTURE = """
            package com.confia.architecture.fixture.permissionedtext;

            import com.confia.shared.web.authorization.PermissionedEndpoint;
            import org.springframework.http.HttpMethod;

            public final class TextPermissionFixture {
                public static PermissionedEndpoint entry() {
                    return new PermissionedEndpoint(HttpMethod.GET, "/api/v1/identity/roles", "identity:read");
                }
            }
            """;
    private static final String CATALOG_FIXTURE = TEXT_FIXTURE
            .replace("TextPermissionFixture", "CatalogPermissionFixture")
            .replace("\"identity:read\"", "com.confia.kernel.Permission.IDENTITY_READ");
    private static final String INCOMPATIBLE_TYPES = "incompatible types";

    @Test
    void anEntryWrittenWithATextInsteadOfAPermissionFailsToCompileForItsType(
            @TempDir Path outputDirectory) {
        Result result = compile(TEXT_FIXTURE, "TextPermissionFixture", outputDirectory);

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics()).anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                && d.getMessage(Locale.ROOT).toLowerCase(Locale.ROOT).contains(INCOMPATIBLE_TYPES)
                && d.getMessage(Locale.ROOT).contains("String")
                && d.getMessage(Locale.ROOT).contains("Permission"));
        assertThat(result.diagnostics()).as("not a missing symbol: the type exists")
                .noneMatch(d -> d.getMessage(Locale.ROOT).contains("cannot find symbol"));
    }

    @Test
    void theSameEntryWrittenWithAValueOfTheCatalogCompilesCleanly(@TempDir Path outputDirectory) {
        Result result = compile(CATALOG_FIXTURE, "CatalogPermissionFixture", outputDirectory,
                "-Xlint:all");

        assertThat(result.success()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    private static Result compile(String source, String simpleName, Path outputDirectory,
            String... extraOptions) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        List<String> options = new ArrayList<>(List.of("-classpath",
                System.getProperty("java.class.path"), "-d", outputDirectory.toString()));
        options.addAll(List.of(extraOptions));
        boolean success = compiler.getTask(null, fileManager, diagnostics, options, null,
                List.of(new InMemorySource(simpleName, source))).call();
        return new Result(success, diagnostics.getDiagnostics());
    }

    private record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
    }

    /** A source file that exists only in memory: never written to, or read from, disk. */
    private static final class InMemorySource extends SimpleJavaFileObject {

        private final String source;

        InMemorySource(String simpleName, String source) {
            super(URI.create("string:///com/confia/architecture/fixture/permissionedtext/"
                    + simpleName + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
