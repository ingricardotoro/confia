package com.confia.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.StaffRole;
import com.confia.kernel.Permission;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Scenarios AZ14 and AZ15 (rbac-permission-matrix-and-audit-integration, design.md decision D3): the
 * matrix of {@link StaffRole} in code and the effective matrix that {@code docs/03-seguridad.md}
 * section 5.2 publishes in a dated note are compared cell by cell, for six roles and eighteen
 * modules, so a permission changed on one side and not on the other breaks the build.
 *
 * <p>The comparison is two pure steps, {@link #differences} and {@link #parse}, so the controls feed
 * them altered copies of either side without writing to disk. The test reads the table of the note,
 * found by its marker, and not the original table of the section, which keeps the column of the
 * guardian and the cells that the four differences changed.
 */
class PermissionMatrixParityTest {

    private static final String DOCS_03 = "docs/03-seguridad.md";
    private static final String MARKER = "**Matriz efectiva de permisos de los seis roles";
    private static final int MODULES = 18;
    private static final int ROLES = 6;

    /** One cell of the matrix: a role code and a module. */
    record Cell(String role, String module) {
    }

    private static final Map<String, String> ROLE_OF_HEADER = Map.of(
            "Super Admin", "super_admin",
            "Administrador", "administrator",
            "Cajero", "cashier",
            "Contabilidad", "accounting",
            "Auditor", "auditor",
            "Coord. Académico", "academic_coordinator");

    private static final Map<Character, String> VERB_OF_LETTER = Map.of(
            'C', "create", 'L', "read", 'A', "update", 'N', "void", 'P', "approve", 'X', "export");

    /** The differences between two matrices, one line per permission that only one side grants. */
    static List<String> differences(Map<Cell, Set<String>> code, Map<Cell, Set<String>> document) {
        List<String> differences = new ArrayList<>();
        Set<Cell> cells = new LinkedHashSet<>(code.keySet());
        cells.addAll(document.keySet());
        for (Cell cell : cells) {
            Set<String> inCode = code.getOrDefault(cell, Set.of());
            Set<String> inDocument = document.getOrDefault(cell, Set.of());
            for (String permission : new TreeSet<>(inCode)) {
                if (!inDocument.contains(permission)) {
                    differences.add(describe(cell, permission, "the code grants it and the document does not"));
                }
            }
            for (String permission : new TreeSet<>(inDocument)) {
                if (!inCode.contains(permission)) {
                    differences.add(describe(cell, permission, "the document grants it and the code does not"));
                }
            }
        }
        return differences;
    }

    /**
     * The matrix of the dated note, given its lines. Fails with {@code marker not found} when the note is
     * absent; an empty table parses to an empty matrix, which the caller's count of cells rejects.
     */
    static Map<Cell, Set<String>> parse(List<String> lines) {
        int marker = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(MARKER)) {
                marker = i;
                break;
            }
        }
        assertThat(marker).as("marker not found: a line with \"%s\" in %s", MARKER, DOCS_03).isNotNegative();

        List<String> roles = null;
        Map<Cell, Set<String>> matrix = new LinkedHashMap<>();
        for (int i = marker + 1; i < lines.size() && lines.get(i).startsWith(">"); i++) {
            String line = lines.get(i);
            if (!line.startsWith("> |")) {
                continue;
            }
            List<String> cells = Arrays.stream(line.substring(2).split("\\|", -1)).skip(1).map(String::strip).toList();
            cells = cells.subList(0, cells.size() - 1);
            if (cells.get(0).equals("Módulo")) {
                roles = cells.subList(1, cells.size()).stream().map(ROLE_OF_HEADER::get).toList();
            } else if (!cells.get(0).startsWith("---") && !cells.get(0).startsWith(":--")) {
                assertThat(roles).as("the header row precedes the first row of the table").isNotNull();
                String module = cells.get(0).replaceFirst("^`([a-z]+)`.*$", "$1");
                for (int column = 0; column < roles.size(); column++) {
                    matrix.put(new Cell(roles.get(column), module), permissionsOf(module, cells.get(column + 1)));
                }
            }
        }
        return matrix;
    }

    @Test
    void theNoteOfTheDocumentIsEvaluatedForSixRolesByEighteenModules() {
        Map<Cell, Set<String>> document = parse(lines(repoRoot().resolve(DOCS_03)));

        assertThat(document).hasSize(ROLES * MODULES);
        assertThat(document.keySet().stream().map(Cell::role).distinct()).hasSize(ROLES);
        assertThat(document.keySet().stream().map(Cell::module).distinct()).hasSize(MODULES);
        assertThat(codeMatrix()).hasSize(ROLES * MODULES);
    }

    @Test
    void theCatalogInCodeMatchesTheEffectiveMatrixOfTheDocumentCellByCell() {
        assertThat(differences(codeMatrix(), parse(lines(repoRoot().resolve(DOCS_03))))).isEmpty();
    }

    @Test
    void anEmptyTableOfTheNoteIsNotAMatrix() {
        List<String> emptyTable = List.of(
                "> " + MARKER + ")**",
                ">",
                "> | Módulo | Super Admin | Administrador | Cajero | Contabilidad | Auditor | Coord. Académico |",
                "> |---|---|---|---|---|---|---|");

        assertThat(parse(emptyTable)).as("a table with a header and no rows has no cell, and 108 are expected")
                .isEmpty();
    }

    @Test
    void aDocumentWithoutTheNoteFailsWithMarkerNotFound() {
        assertThatThrownBy(() -> parse(List.of("# 5.2 Matriz de permisos por rol", "| Módulo | Super Admin |")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("marker not found");
    }

    @Test
    void aCopyOfTheCodeThatGivesTheAdministratorPaymentsCreateIsRejectedNamingRoleModuleAndPermission() {
        Map<Cell, Set<String>> altered = copyOf(codeMatrix());
        altered.get(new Cell("administrator", "payments")).add("payments:create");

        assertThat(differences(altered, parse(lines(repoRoot().resolve(DOCS_03)))))
                .hasSize(1)
                .singleElement().asString()
                .contains("administrator").contains("payments").contains("payments:create")
                .contains("the code grants it and the document does not");
    }

    @Test
    void aCopyOfTheDocumentWithoutTheAuditorsAuditExportIsRejectedNamingRoleModuleAndPermission() {
        Map<Cell, Set<String>> altered = copyOf(parse(lines(repoRoot().resolve(DOCS_03))));
        assertThat(altered.get(new Cell("auditor", "audit")).remove("audit:export")).isTrue();

        assertThat(differences(codeMatrix(), altered))
                .hasSize(1)
                .singleElement().asString()
                .contains("auditor").contains("audit").contains("audit:export")
                .contains("the code grants it and the document does not");
    }

    @Test
    void aPermissionOnlyTheDocumentGrantsIsRejectedToo() {
        Map<Cell, Set<String>> document = copyOf(codeMatrix());
        document.get(new Cell("cashier", "ledger")).add("ledger:void");

        assertThat(differences(codeMatrix(), document)).singleElement().asString()
                .contains("cashier").contains("ledger").contains("ledger:void")
                .contains("the document grants it and the code does not");
    }

    @Test
    void identicalMatricesHaveNoDifferences() {
        assertThat(differences(codeMatrix(), copyOf(codeMatrix()))).isEmpty();
    }

    /** The matrix of {@link StaffRole}, one cell per role and module, empty when the role has no access. */
    static Map<Cell, Set<String>> codeMatrix() {
        Map<Cell, Set<String>> matrix = new LinkedHashMap<>();
        Set<String> modules = new LinkedHashSet<>();
        Arrays.stream(Permission.values()).forEach(permission -> modules.add(permission.module()));
        for (StaffRole role : StaffRole.values()) {
            for (String module : modules) {
                Set<String> granted = new TreeSet<>();
                role.permissions().stream().filter(permission -> permission.module().equals(module))
                        .forEach(permission -> granted.add(permission.code()));
                matrix.put(new Cell(role.code(), module), granted);
            }
        }
        return matrix;
    }

    private static Set<String> permissionsOf(String module, String cell) {
        Set<String> permissions = new TreeSet<>();
        if (cell.equals("—")) {
            return permissions;
        }
        for (String letter : cell.split(" ")) {
            if (letter.equals("S")) {
                permissions.add(module + ":request-void");
            } else {
                String verb = VERB_OF_LETTER.get(letter.charAt(0));
                assertThat(verb).as("the letter \"%s\" of the cell \"%s\" of module %s", letter, cell, module)
                        .isNotNull();
                permissions.add(module + ":" + verb);
            }
        }
        return permissions;
    }

    private static Map<Cell, Set<String>> copyOf(Map<Cell, Set<String>> matrix) {
        Map<Cell, Set<String>> copy = new LinkedHashMap<>();
        matrix.forEach((cell, permissions) -> copy.put(cell, new TreeSet<>(permissions)));
        return copy;
    }

    private static String describe(Cell cell, String permission, String reason) {
        return String.format("role %s, module %s, permission %s: %s", cell.role(), cell.module(), permission, reason);
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
