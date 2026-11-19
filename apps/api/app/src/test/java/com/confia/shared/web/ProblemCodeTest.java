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
                        "idempotency-conflict", "unsupported-media-type",
                        "idempotency-key-missing", "idempotency-payload-mismatch",
                        "too-many-requests", "internal-error", "capacity-exceeded", "token-invalid",
                        "token-expired", "csrf-invalid", "authentication-failed", "second-factor-invalid",
                        "session-expired", "mfa-already-enrolled", "academic-year-overlap",
                        "academic-year-already-active", "academic-year-closed", "duplicate-name",
                        "inactive-reference", "duplicate-student-code", "duplicate-national-id",
                        "student-not-active", "section-full", "duplicate-active-enrollment",
                        "invalid-enrollment-state", "transfer-year-mismatch", "invalid-responsibility-split",
                        "guardian-not-active", "consent-channel-unavailable", "import-not-simulated",
                        "import-file-changed", "duplicate-payment-concept-code", "price-validity-overlap",
                        "price-list-archived", "duplicate-tax-rate-code", "tax-rate-period-overlap", "plan-not-editable", "plan-without-items",
                        "duplicate-plan-item", "plan-item-invalid", "accrual-run-in-progress", "charge-already-voided", "charge-not-voidable",
                        "duplicate-account-code", "account-in-use", "posting-account-invalid", "stale-version",
                        "cash-session-already-open", "cash-session-not-open", "cash-session-not-awaiting-approval", "cash-difference-reason-required", "cash-expected-negative", "cash-approval-same-actor");
    }

    @Test
    void everyCodeIsUniqueAndKebabCase() {
        List<String> codes = Arrays.stream(ProblemCode.values()).map(ProblemCode::code).toList();
        assertThat(codes).as("a code can never repeat").doesNotHaveDuplicates();
        assertThat(codes).allSatisfy(code -> assertThat(code).matches(KEBAB_CASE));
    }

    @ParameterizedTest
    @CsvSource({"validation-failed, 400", "idempotency-key-missing, 400",
            "authentication-required, 401", "forbidden, 403",
            "resource-not-found, 404", "method-not-allowed, 405", "idempotency-conflict, 409",
            "unsupported-media-type, 415", "idempotency-payload-mismatch, 422",
            "too-many-requests, 429", "internal-error, 500", "capacity-exceeded, 503",
            "token-invalid, 401", "token-expired, 401", "csrf-invalid, 403",
            "authentication-failed, 401", "second-factor-invalid, 401", "session-expired, 401",
            "mfa-already-enrolled, 409", "academic-year-overlap, 409",
            "academic-year-already-active, 409", "academic-year-closed, 409", "duplicate-name, 409",
            "inactive-reference, 409", "duplicate-student-code, 409", "duplicate-national-id, 409",
            "student-not-active, 409", "section-full, 409", "duplicate-active-enrollment, 409",
            "invalid-enrollment-state, 409", "transfer-year-mismatch, 409",
            "invalid-responsibility-split, 400", "guardian-not-active, 409",
            "consent-channel-unavailable, 409", "import-not-simulated, 409", "import-file-changed, 409",
            "duplicate-payment-concept-code, 409", "price-validity-overlap, 409", "price-list-archived, 409",
            "duplicate-tax-rate-code, 409", "tax-rate-period-overlap, 409", "plan-not-editable, 409",
            "plan-without-items, 409", "duplicate-plan-item, 409", "plan-item-invalid, 409", "accrual-run-in-progress, 409", "charge-already-voided, 409", "charge-not-voidable, 409",
            "duplicate-account-code, 409", "account-in-use, 409", "posting-account-invalid, 409", "stale-version, 409",
            "cash-session-already-open, 409", "cash-session-not-open, 409", "cash-session-not-awaiting-approval, 409", "cash-difference-reason-required, 400", "cash-expected-negative, 409", "cash-approval-same-actor, 403"})
    void eachCodeCarriesItsHttpStatus(String code, int status) {
        assertThat(ProblemCode.ofCode(code)).get().extracting(ProblemCode::status)
                .isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"validation-failed", "authentication-required", "forbidden",
            "resource-not-found", "method-not-allowed", "unsupported-media-type",
            "too-many-requests", "internal-error", "capacity-exceeded", "idempotency-conflict",
            "idempotency-payload-mismatch", "idempotency-key-missing", "token-invalid",
            "token-expired", "csrf-invalid", "authentication-failed", "second-factor-invalid",
            "session-expired", "mfa-already-enrolled", "academic-year-overlap",
            "academic-year-already-active", "academic-year-closed", "duplicate-name",
            "inactive-reference", "duplicate-student-code", "duplicate-national-id",
            "student-not-active", "section-full", "duplicate-active-enrollment", "invalid-enrollment-state",
            "transfer-year-mismatch", "invalid-responsibility-split", "guardian-not-active",
            "consent-channel-unavailable", "import-not-simulated", "import-file-changed",
            "duplicate-payment-concept-code", "price-validity-overlap", "price-list-archived",
            "duplicate-tax-rate-code", "tax-rate-period-overlap", "plan-not-editable", "plan-without-items",
            "duplicate-plan-item", "plan-item-invalid", "accrual-run-in-progress", "charge-already-voided", "charge-not-voidable",
            "duplicate-account-code", "account-in-use", "posting-account-invalid", "stale-version",
            "cash-session-already-open", "cash-session-not-open", "cash-session-not-awaiting-approval", "cash-difference-reason-required", "cash-expected-negative", "cash-approval-same-actor"})
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
    @ValueSource(strings = {
            "institution-not-found", "institution-inactive", "Forbidden", "", "forbidden "})
    void anUnknownOrMisspelledCodeIsNotACode(String code) {
        assertThat(ProblemCode.ofCode(code)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"400, validation-failed", "401, authentication-required", "403, forbidden",
            "404, resource-not-found", "405, method-not-allowed", "406, validation-failed",
            "409, validation-failed", "413, validation-failed", "415, unsupported-media-type",
            "429, too-many-requests", "431, validation-failed", "500, internal-error",
            "503, capacity-exceeded"})
    void theCodeOfAnHttpStatusIsTheOneRuleTheValveAndTheTranslatorShare(int status, String code) {
        assertThat(ProblemCode.forStatus(status).code()).isEqualTo(code);
    }

    @Test
    void aNullCodeIsNotACodeEither() {
        assertThat(ProblemCode.ofCode(null)).isEmpty();
    }
}
