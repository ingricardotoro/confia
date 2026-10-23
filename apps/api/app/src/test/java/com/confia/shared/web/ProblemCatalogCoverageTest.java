package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.problem.ProblemCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Specs/web-edge, requirement "Catálogo de mensajes del backend en español de Honduras" (design.md
 * decision 10): every error code has a {@code title} and a {@code detail} in the catalog, and every
 * catalog entry belongs to a code. Both directions fail naming the code or the entry. The checks
 * are plain functions over a {@link Properties} value, so each can be shown to fail on a catalog
 * that is wrong on purpose and not only to pass on the real one.
 */
class ProblemCatalogCoverageTest {

    private static final String CATALOG = "/i18n/problems.properties";
    private static final Pattern ENTRY = Pattern.compile("problem\\.(.+)\\.(title|detail)");

    private static final Set<String> CODES_OF_LATER_CHANGES = Set.of(
            "institution-not-found", "institution-inactive");

    /** Codes that have no non-blank {@code title} or {@code detail} in {@code catalog}. */
    static List<String> codesWithoutAMessage(Properties catalog) {
        List<String> missing = new ArrayList<>();
        for (ProblemCode code : ProblemCode.values()) {
            for (String key : List.of(code.titleKey(), code.detailKey())) {
                String value = catalog.getProperty(key);
                if (value == null || value.isBlank()) {
                    missing.add(code.code() + " (" + key + ")");
                }
            }
        }
        return missing;
    }

    /** Catalog keys that name no code of {@link ProblemCode} or do not follow the key pattern. */
    static List<String> entriesWithoutACode(Properties catalog) {
        Set<String> known = new TreeSet<>();
        Arrays.stream(ProblemCode.values()).forEach(code -> known.add(code.code()));
        List<String> orphans = new ArrayList<>();
        for (String key : new TreeSet<>(catalog.stringPropertyNames())) {
            Matcher match = ENTRY.matcher(key);
            if (!match.matches() || !known.contains(match.group(1))) {
                orphans.add(key);
            }
        }
        return orphans;
    }

    static Properties loadCatalog() {
        Properties catalog = new Properties();
        try (InputStream in = ProblemCatalogCoverageTest.class.getResourceAsStream(CATALOG)) {
            assertThat(in).as("the catalog %s must be on the class path", CATALOG).isNotNull();
            catalog.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return catalog;
    }

    @Test
    void everyCodeHasATitleAndADetailInTheRealCatalog() {
        Properties catalog = loadCatalog();
        assertThat(catalog.stringPropertyNames())
                .as("non-vacuous: the catalog holds entries").isNotEmpty();
        assertThat(codesWithoutAMessage(catalog)).isEmpty();
    }

    @Test
    void everyEntryOfTheRealCatalogBelongsToACode() {
        assertThat(entriesWithoutACode(loadCatalog())).isEmpty();
    }

    @Test
    void aCodeWithoutAnEntryFailsNamingTheCode() {
        Properties catalog = loadCatalog();
        catalog.remove(ProblemCode.FORBIDDEN.detailKey());
        catalog.setProperty(ProblemCode.INTERNAL_ERROR.titleKey(), "   ");

        assertThat(codesWithoutAMessage(catalog)).containsExactlyInAnyOrder(
                "forbidden (problem.forbidden.detail)",
                "internal-error (problem.internal-error.title)");
    }

    @Test
    void anEntryWithoutACodeFailsNamingTheEntry() {
        Properties catalog = loadCatalog();
        catalog.setProperty("problem.no-such-code.title", "x");
        catalog.setProperty("unrelated.key", "x");

        assertThat(entriesWithoutACode(catalog))
                .containsExactlyInAnyOrder("problem.no-such-code.title", "unrelated.key");
    }

    @Test
    void theTwoCodesOfTheTranslatorHaveTheirOwnEntriesInTheCatalog() {
        Properties catalog = loadCatalog();
        for (String code : List.of("resource-not-found", "unsupported-media-type")) {
            assertThat(ProblemCode.ofCode(code)).as("code %s", code).isPresent();
            assertThat(catalog.getProperty("problem." + code + ".title")).as("title of %s", code)
                    .isNotBlank();
            assertThat(catalog.getProperty("problem." + code + ".detail")).as("detail of %s", code)
                    .isNotBlank();
        }
    }

    @Test
    void theTwoCodesOfTheLimiterHaveTheirOwnEntriesInTheCatalog() {
        Properties catalog = loadCatalog();
        for (String code : List.of("too-many-requests", "capacity-exceeded")) {
            assertThat(ProblemCode.ofCode(code)).as("code %s", code).isPresent();
            assertThat(catalog.getProperty("problem." + code + ".title")).as("title of %s", code)
                    .isNotBlank();
            assertThat(catalog.getProperty("problem." + code + ".detail")).as("detail of %s", code)
                    .isNotBlank();
        }
    }

    @Test
    void theThreeCodesOfTheIdempotencyEdgeHaveTheirOwnEntriesInTheCatalog() {
        Properties catalog = loadCatalog();
        for (String code : List.of("idempotency-key-missing", "idempotency-conflict",
                "idempotency-payload-mismatch")) {
            assertThat(ProblemCode.ofCode(code)).as("code %s", code).isPresent();
            assertThat(catalog.getProperty("problem." + code + ".title")).as("title of %s", code)
                    .isNotBlank();
            assertThat(catalog.getProperty("problem." + code + ".detail")).as("detail of %s", code)
                    .isNotBlank();
        }
    }

    @Test
    void theTwoCodesOfTheTokenFilterHaveTheirOwnEntriesInTheCatalog() {
        Properties catalog = loadCatalog();
        for (String code : List.of("token-invalid", "token-expired")) {
            assertThat(ProblemCode.ofCode(code)).as("code %s", code).isPresent();
            assertThat(catalog.getProperty("problem." + code + ".title")).as("title of %s", code)
                    .isNotBlank();
            assertThat(catalog.getProperty("problem." + code + ".detail")).as("detail of %s", code)
                    .isNotBlank();
        }
        assertThat(codesWithoutAMessage(catalog)).isEmpty();
    }

    @Test
    void theCsrfCodeHasItsOwnEntriesInTheCatalogAndACatalogWithoutThemFails() {
        Properties catalog = loadCatalog();

        assertThat(ProblemCode.ofCode("csrf-invalid")).isPresent();
        assertThat(catalog.getProperty("problem.csrf-invalid.title")).isNotBlank();
        assertThat(catalog.getProperty("problem.csrf-invalid.detail")).isNotBlank();
        catalog.remove("problem.csrf-invalid.title");
        catalog.remove("problem.csrf-invalid.detail");
        assertThat(codesWithoutAMessage(catalog)).containsExactlyInAnyOrder(
                "csrf-invalid (problem.csrf-invalid.title)",
                "csrf-invalid (problem.csrf-invalid.detail)");
    }

    @Test
    void aCatalogWithoutTheSpanishEntryOfTokenExpiredFailsNamingIt() {
        Properties catalog = loadCatalog();
        catalog.remove("problem.token-expired.title");
        catalog.remove("problem.token-expired.detail");

        assertThat(codesWithoutAMessage(catalog)).containsExactlyInAnyOrder(
                "token-expired (problem.token-expired.title)",
                "token-expired (problem.token-expired.detail)");
    }

    @Test
    void theAuthenticationFailedCodeHasItsSpanishEntriesAndACatalogWithoutThemFails() {
        Properties catalog = loadCatalog();

        assertThat(ProblemCode.ofCode("authentication-failed")).isPresent();
        assertThat(catalog.getProperty("problem.authentication-failed.title"))
                .isEqualTo("Credenciales inválidas");
        assertThat(catalog.getProperty("problem.authentication-failed.detail")).isEqualTo(
                "El identificador o la contraseña no son correctos. Revíselos e inténtelo de nuevo.");
        catalog.remove("problem.authentication-failed.title");
        catalog.remove("problem.authentication-failed.detail");
        assertThat(codesWithoutAMessage(catalog)).containsExactlyInAnyOrder(
                "authentication-failed (problem.authentication-failed.title)",
                "authentication-failed (problem.authentication-failed.detail)");
    }

    @Test
    void aCatalogEntryOfAnotherChangesCodeIsAnOrphanForThisOne() {
        Properties catalog = loadCatalog();
        catalog.setProperty("problem.institution-inactive.title", "x");

        assertThat(entriesWithoutACode(catalog))
                .containsExactly("problem.institution-inactive.title");
    }

    @Test
    void theInstitutionCodesOfALaterChangeDoNotExistYet() {
        Properties catalog = loadCatalog();
        for (String later : CODES_OF_LATER_CHANGES) {
            assertThat(ProblemCode.ofCode(later)).as("code %s", later).isEmpty();
            assertThat(catalog.stringPropertyNames())
                    .as("catalog entries of %s", later)
                    .noneMatch(key -> key.contains("." + later + "."));
        }
        assertThat(ProblemCode.ofCode("authentication-required")).isPresent();
    }
}
