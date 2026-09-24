package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.DomainException;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The catalog of {@code shared.security}'s idempotency error codes (ADR-0019, "Cumplimiento",
 * point 2; design.md, decision 9, "Los códigos nuevos siguen la convención del catálogo por
 * módulo"): mirrors {@code KernelErrorCodesTest} and {@code OrganizationErrorCodesTest}'s pattern —
 * kebab-case format, at most 64 characters, no duplicates anywhere in the catalog, both codes
 * sharing the {@code idempotency-} prefix, and every exception that declares one is a {@link
 * DomainException} subclass.
 *
 * <p>Two codes only: {@link IdempotencyConflictException#CODE} ({@code idempotency-conflict}),
 * shared by both the {@code WAIT_EXHAUSTED} and {@code MARKER_IN_PROGRESS} reasons — the reason is
 * data carried by the exception, not a separate stable code — and {@link
 * IdempotencyPayloadMismatchException#CODE} ({@code idempotency-payload-mismatch}).
 */
class IdempotencyErrorCodesTest {

    private static final Pattern KEBAB_CASE = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    private static final int MAX_CODE_LENGTH = 64;
    private static final String MODULE_PREFIX = "idempotency-";

    @Test
    void catalogHasExactlyTheTwoDefinedCodes() {
        assertThat(allIdempotencyErrorCodes()).containsExactlyInAnyOrder(
                "idempotency-conflict",
                "idempotency-payload-mismatch");
    }

    @Test
    void everyCodeIsUniqueAcrossTheCatalog() {
        List<String> codes = allIdempotencyErrorCodes();

        assertThat(codes).doesNotHaveDuplicates();
    }

    @Test
    void everyCodeFollowsTheKebabCaseFormatAndMaximumLength() {
        for (String code : allIdempotencyErrorCodes()) {
            assertThat(code).as("code '%s' must be kebab-case", code).matches(KEBAB_CASE);
            assertThat(code.length())
                    .as("code '%s' must be at most %d characters", code, MAX_CODE_LENGTH)
                    .isLessThanOrEqualTo(MAX_CODE_LENGTH);
        }
    }

    @Test
    void theKebabCasePatternRejectsAMalformedCode() {
        // Without this, the format check above could pass against a pattern that accepts anything.
        assertThat("Idempotency-Conflict").doesNotMatch(KEBAB_CASE);
        assertThat("idempotency_conflict").doesNotMatch(KEBAB_CASE);
        assertThat("idempotency--conflict").doesNotMatch(KEBAB_CASE);
        assertThat("-idempotency-conflict").doesNotMatch(KEBAB_CASE);
    }

    @Test
    void everyCodeCarriesTheModulePrefix() {
        for (String code : allIdempotencyErrorCodes()) {
            assertThat(code).as("code '%s' must start with '%s'", code, MODULE_PREFIX)
                    .startsWith(MODULE_PREFIX);
        }
    }

    @Test
    void everyExceptionThatDeclaresAnIdempotencyCodeIsADomainException() {
        assertThat(new IdempotencyConflictException(IdempotencyConflictException.Reason.WAIT_EXHAUSTED))
                .isInstanceOf(DomainException.class);
        assertThat(
                new IdempotencyConflictException(IdempotencyConflictException.Reason.MARKER_IN_PROGRESS))
                .isInstanceOf(DomainException.class);
        assertThat(new IdempotencyPayloadMismatchException()).isInstanceOf(DomainException.class);
    }

    @Test
    void bothReasonsOfTheConflictExceptionShareTheSameCode() {
        assertThat(new IdempotencyConflictException(IdempotencyConflictException.Reason.WAIT_EXHAUSTED)
                .code())
                .isEqualTo(IdempotencyConflictException.CODE);
        assertThat(
                new IdempotencyConflictException(IdempotencyConflictException.Reason.MARKER_IN_PROGRESS)
                        .code())
                .isEqualTo(IdempotencyConflictException.CODE);
    }

    private static List<String> allIdempotencyErrorCodes() {
        return List.of(
                IdempotencyConflictException.CODE,
                IdempotencyPayloadMismatchException.CODE);
    }
}
