package com.confia.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Specs/build-integrity, scenarios BI126 and I292 (rbac-permission-matrix-and-audit-integration
 * design.md D15): once {@code V15} dropped the second factor flag column of {@code
 * identity_staff_account}, nothing under {@code apps/} names it any more, in any case, and the jOOQ
 * code generated from the migrated schema does not carry it either. The second factor is decided by
 * {@code MfaPolicy}; a leftover reference would be a fixture that still inserts a column that no
 * longer exists, or code that reads a flag nothing writes.
 *
 * <p><b>The exemptions are closed and named.</b> {@code V6} created the column and {@code V15} drops
 * it, and a migration applied in production is never edited. A test that pins a throw-away database
 * to a version before 15 with {@code StepwiseDatabase.migratedTo("<n>")} keeps the column, because
 * on that version it is {@code NOT NULL}. {@code DocumentationNotesTest} is the one exemption added
 * by owner decision: it asserts the dated note of {@code docs/03} section 4.3, which must keep
 * naming the column. This test names the pattern, so it exempts itself.
 *
 * <p>The search reads files, not classes, because the comment of a class and the name of a test
 * method count as a reference too, which no class scan sees. The detector is proven on a fixture
 * tree first: it must name the file that brings a reference back, accept the exempt shapes, and the
 * real scan must not be vacuous.
 */
class MfaRequiredAbsenceTest {

    /** The three spellings of the retired column and of the accessor of the removed field. */
    private static final Pattern REFERENCE = Pattern.compile("(?i)mfa_?required");

    private static final Pattern PINNED_VERSION = Pattern.compile("migratedTo\\(\"(\\d+)\"\\)");

    private static final String MIGRATIONS = "apps/api/app/src/main/resources/db/migration/";

    private static final Set<String> EXEMPT_FILES = Set.of(
            MIGRATIONS + "V6__add_mfa_required_and_create_crypto_mfa_tables.sql",
            MIGRATIONS + "V15__drop_identity_staff_account_mfa_required.sql",
            // It asserts the dated docs/03 section 4.3 note, which must keep naming the column
            // (exemption added by owner decision, task 6 of the change).
            "apps/api/app/src/test/java/com/confia/architecture/DocumentationNotesTest.java",
            "apps/api/app/src/test/java/com/confia/architecture/MfaRequiredAbsenceTest.java");

    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of("target", "node_modules", ".git", "dist", "build", ".turbo");

    private static final long MAX_BYTES = 2_000_000L;

    @Test
    void noFileUnderAppsNamesTheRetiredColumnExceptTheNamedExemptions() throws IOException {
        Path root = repositoryRoot();

        List<String> scanned = textFiles(root.resolve("apps"), root);
        List<String> findings = findings(root);

        assertThat(scanned).as("the scan sees the real tree, so the absence is not vacuous")
                .contains("apps/api/app/src/main/java/com/confia/identity/domain/StaffAccount.java",
                        MIGRATIONS + "V6__add_mfa_required_and_create_crypto_mfa_tables.sql")
                .hasSizeGreaterThan(300);
        assertThat(findings).as("files that still name the retired column").isEmpty();
    }

    @Test
    void theJooqCodeGeneratedFromTheMigratedSchemaDoesNotCarryTheColumn() throws IOException {
        Path generated = Paths.get("target/generated-sources/jooq").toAbsolutePath();

        List<Path> files;
        try (Stream<Path> walk = Files.walk(generated)) {
            files = walk.filter(path -> path.toString().endsWith(".java")).toList();
        }

        assertThat(files).as("non-vacuous: the generated code of the staff account table exists")
                .anyMatch(path -> path.getFileName().toString().equals("IdentityStaffAccount.java"));
        assertThat(files.stream().filter(MfaRequiredAbsenceTest::mentionsTheColumn)
                .map(path -> path.getFileName().toString()).toList())
                .as("generated files that still carry the retired column").isEmpty();
    }

    @Test
    void theDetectorNamesTheFileThatBringsAReferenceBack(@TempDir Path fixture) throws IOException {
        write(fixture, "apps/api/app/src/test/java/com/confia/identity/FixtureHelper.java",
                "insert into identity_staff_account (id, mfa_required) values (?, true)");
        write(fixture, "apps/api/app/src/test/java/com/confia/identity/OtherHelper.java",
                "StaffAccount account = seed(/* the flag */ mfaRequired);");
        write(fixture, "apps/api/app/src/main/java/com/confia/identity/Repo.java",
                "return row.getMfaRequired();");
        write(fixture, "apps/api/app/src/main/java/com/confia/identity/Clean.java",
                "final class Clean {}");

        assertThat(findings(fixture)).containsExactlyInAnyOrder(
                "apps/api/app/src/main/java/com/confia/identity/Repo.java",
                "apps/api/app/src/test/java/com/confia/identity/FixtureHelper.java",
                "apps/api/app/src/test/java/com/confia/identity/OtherHelper.java");
    }

    @Test
    void theDetectorAcceptsTheExemptShapesAndNothingElse(@TempDir Path fixture) throws IOException {
        String reference = "insert into identity_staff_account (id, mfa_required) values (?, true)";
        write(fixture, MIGRATIONS + "V6__add_mfa_required_and_create_crypto_mfa_tables.sql", reference);
        write(fixture, MIGRATIONS + "V15__drop_identity_staff_account_mfa_required.sql", reference);
        write(fixture, "apps/api/app/src/test/java/com/confia/architecture/DocumentationNotesTest.java",
                reference);
        write(fixture, "apps/api/app/src/test/java/com/confia/schema/Pinned.java",
                "StepwiseDatabase.migratedTo(\"14\"); " + reference);
        write(fixture, "apps/api/app/src/test/java/com/confia/schema/PinnedAndCurrent.java",
                "StepwiseDatabase.migratedTo(\"15\"); StepwiseDatabase.migratedTo(\"9\"); " + reference);
        write(fixture, "apps/api/app/src/test/java/com/confia/schema/NotPinnedEnough.java",
                "StepwiseDatabase.migratedTo(\"15\"); " + reference);
        write(fixture, "apps/api/app/src/test/java/com/confia/schema/Unpinned.java", reference);
        write(fixture, "apps/api/app/target/generated/Ignored.java", reference);

        assertThat(findings(fixture)).containsExactlyInAnyOrder(
                "apps/api/app/src/test/java/com/confia/schema/NotPinnedEnough.java",
                "apps/api/app/src/test/java/com/confia/schema/Unpinned.java");
    }

    @Test
    void thePatternMatchesEverySpellingOfTheColumnAndTheAccessor() {
        for (String spelling : List.of("mfa_required", "MFA_REQUIRED", "mfaRequired", "MfaRequired",
                "getMfaRequired", "Mfa_Required")) {
            assertThat(REFERENCE.matcher(spelling).find()).as(spelling).isTrue();
        }
        for (String other : List.of("mfaNotRequired", "MfaPolicy", "secondFactorRequired",
                "mfa_totp_credential")) {
            assertThat(REFERENCE.matcher(other).find()).as(other).isFalse();
        }
    }

    /** The relative paths, with forward slashes, of the files under {@code root/apps} that name the column. */
    private static List<String> findings(Path root) throws IOException {
        return textFiles(root.resolve("apps"), root).stream()
                .filter(relative -> !EXEMPT_FILES.contains(relative))
                .filter(relative -> mentionsTheColumn(root.resolve(relative)))
                .filter(relative -> !isPinnedBeforeV15(root.resolve(relative)))
                .sorted().collect(Collectors.toList());
    }

    private static List<String> textFiles(Path apps, Path root) throws IOException {
        if (!Files.isDirectory(apps)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(apps)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> !insideSkippedDirectory(apps, path))
                    .filter(path -> sizeOf(path) <= MAX_BYTES)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .sorted().toList();
        }
    }

    private static boolean insideSkippedDirectory(Path apps, Path file) {
        Path relative = apps.relativize(file);
        for (int index = 0; index < relative.getNameCount() - 1; index++) {
            if (SKIPPED_DIRECTORIES.contains(relative.getName(index).toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsTheColumn(Path file) {
        String text = read(file);
        return text != null && REFERENCE.matcher(text).find();
    }

    /** A test that migrates a throw-away database to a version before 15 keeps the column. */
    private static boolean isPinnedBeforeV15(Path file) {
        String text = read(file);
        if (text == null) {
            return false;
        }
        Matcher matcher = PINNED_VERSION.matcher(text);
        while (matcher.find()) {
            if (Integer.parseInt(matcher.group(1)) < 15) {
                return true;
            }
        }
        return false;
    }

    /** The text of the file, or null for a binary file, which cannot name a column. */
    private static String read(Path file) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString();
        } catch (CharacterCodingException binary) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("docs/adr"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Could not find the repository root from " + Paths.get("").toAbsolutePath());
    }
}
