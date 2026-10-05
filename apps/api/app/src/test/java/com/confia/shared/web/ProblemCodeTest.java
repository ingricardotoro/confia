package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.problem.ProblemCode;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specs/web-edge, requirements "El {@code type} del error se deriva del código estable del error"
 * and "Catálogo de códigos de error y estados HTTP con productor en este cambio" (design.md
 * decision 8): the codes this change can produce, their status, and a {@code type} that depends on
 * the code and on nothing else.
 */
class ProblemCodeTest {

    private static final Pattern KEBAB_CASE = Pattern.compile("[a-z]+(-[a-z]+)*");

    @Test
    void thisChangeOwnsExactlyTheCodesItCanProduce() {
        assertThat(Arrays.stream(ProblemCode.values()).map(ProblemCode::code))
                .containsExactlyInAnyOrder("validation-failed", "authentication-required",
                        "forbidden", "resource-not-found", "method-not-allowed",
                        "unsupported-media-type", "internal-error");
    }

    @Test
    void everyCodeIsUniqueAndKebabCase() {
        List<String> codes = Arrays.stream(ProblemCode.values()).map(ProblemCode::code).toList();
        assertThat(codes).as("a code can never repeat").doesNotHaveDuplicates();
        assertThat(codes).allSatisfy(code -> assertThat(code).matches(KEBAB_CASE));
    }

    @ParameterizedTest
    @CsvSource({"validation-failed, 400", "authentication-required, 401", "forbidden, 403",
            "resource-not-found, 404", "method-not-allowed, 405", "unsupported-media-type, 415",
            "internal-error, 500"})
    void eachCodeCarriesItsHttpStatus(String code, int status) {
        assertThat(ProblemCode.ofCode(code)).get().extracting(ProblemCode::status)
                .isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"validation-failed", "authentication-required", "forbidden",
            "resource-not-found", "method-not-allowed", "unsupported-media-type",
            "internal-error"})
    void theTypeIsTheStableBaseFollowedByTheCodeAndNothingElse(String code) {
        ProblemCode problem = ProblemCode.ofCode(code).orElseThrow();
        assertThat(problem.type()).isEqualTo(URI.create("https://confia.hn/problems/" + code));
        assertThat(problem.type()).as("the same code always yields the same type")
                .isEqualTo(ProblemCode.ofCode(code).orElseThrow().type());
    }

    @Test
    void differentCodesYieldDifferentTypes() {
        Set<URI> types = new HashSet<>();
        for (ProblemCode problem : ProblemCode.values()) {
            types.add(problem.type());
        }
        assertThat(types).hasSize(ProblemCode.values().length);
    }

    @Test
    void messageKeysFollowThePatternTheCatalogUses() {
        assertThat(ProblemCode.AUTHENTICATION_REQUIRED.titleKey())
                .isEqualTo("problem.authentication-required.title");
        assertThat(ProblemCode.AUTHENTICATION_REQUIRED.detailKey())
                .isEqualTo("problem.authentication-required.detail");
    }

    @ParameterizedTest
    @ValueSource(strings = {"authentication-failed", "token-invalid", "token-expired",
            "institution-not-found", "institution-inactive", "Forbidden", "", "forbidden "})
    void anUnknownOrMisspelledCodeIsNotACode(String code) {
        assertThat(ProblemCode.ofCode(code)).isEmpty();
    }

    @Test
    void aNullCodeIsNotACodeEither() {
        assertThat(ProblemCode.ofCode(null)).isEmpty();
    }
}
