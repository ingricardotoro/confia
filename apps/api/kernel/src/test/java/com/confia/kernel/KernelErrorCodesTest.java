package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The complete, closed catalog of {@code kernel}'s eight error codes (ADR-0019; design.md,
 * decision 2): kebab-case format, uniqueness across every exception in the module, and that each
 * one belongs to a {@link DomainException} subclass. These eight codes are definitive; changing
 * one requires updating design.md and specs/money/spec.md in the same commit.
 */
class KernelErrorCodesTest {

    private static final Pattern KEBAB_CASE = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");

    @Test
    void catalogHasExactlyTheEightDefinedCodes() {
        assertThat(allKernelErrorCodes()).containsExactlyInAnyOrder(
                "currency-mismatch",
                "currency-unsupported",
                "money-amount-malformed",
                "money-scale-exceeded",
                "money-amount-out-of-range",
                "percentage-malformed",
                "percentage-scale-exceeded",
                "percentage-out-of-range");
    }

    @Test
    void everyCodeIsUniqueAcrossAllKernelExceptions() {
        List<String> codes = allKernelErrorCodes();

        assertThat(codes).doesNotHaveDuplicates();
    }

    @Test
    void everyCodeFollowsTheKebabCaseFormat() {
        for (String code : allKernelErrorCodes()) {
            assertThat(code).as("code '%s' must be kebab-case", code).matches(KEBAB_CASE);
        }
    }

    @Test
    void theKebabCasePatternRejectsAMalformedCode() {
        // Without this, the format check above could pass against a pattern that accepts anything.
        assertThat("Currency-Mismatch").doesNotMatch(KEBAB_CASE);
        assertThat("currency_mismatch").doesNotMatch(KEBAB_CASE);
        assertThat("currency--mismatch").doesNotMatch(KEBAB_CASE);
        assertThat("-currency-mismatch").doesNotMatch(KEBAB_CASE);
    }

    @Test
    void everyExceptionThatDeclaresAKernelCodeIsADomainException() {
        assertThat(new CurrencyMismatchException(CurrencyCode.HNL, CurrencyCode.USD))
                .isInstanceOf(DomainException.class);
        assertThat(new UnsupportedCurrencyException()).isInstanceOf(DomainException.class);
        assertThat(InvalidMoneyAmountException.malformed()).isInstanceOf(DomainException.class);
        assertThat(InvalidMoneyAmountException.scaleExceeded(6)).isInstanceOf(DomainException.class);
        assertThat(InvalidMoneyAmountException.outOfRange()).isInstanceOf(DomainException.class);
        assertThat(InvalidPercentageException.malformed()).isInstanceOf(DomainException.class);
        assertThat(InvalidPercentageException.scaleExceeded(6))
                .isInstanceOf(DomainException.class);
        assertThat(InvalidPercentageException.outOfRange()).isInstanceOf(DomainException.class);
    }

    private static List<String> allKernelErrorCodes() {
        return List.of(
                CurrencyMismatchException.CODE,
                UnsupportedCurrencyException.CODE,
                InvalidMoneyAmountException.MALFORMED,
                InvalidMoneyAmountException.SCALE_EXCEEDED,
                InvalidMoneyAmountException.OUT_OF_RANGE,
                InvalidPercentageException.MALFORMED,
                InvalidPercentageException.SCALE_EXCEEDED,
                InvalidPercentageException.OUT_OF_RANGE);
    }
}
