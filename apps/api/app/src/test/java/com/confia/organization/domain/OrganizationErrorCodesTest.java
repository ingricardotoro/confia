package com.confia.organization.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.DomainException;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The catalog of {@code organization.domain}'s error codes (ADR-0019, "Cumplimiento", point 2,
 * extended to this module; specs/organization/spec.md, requirement "Catálogo de códigos del
 * módulo: formato y ausencia de repetidos"): mirrors {@code KernelErrorCodesTest}'s pattern —
 * kebab-case format, at most 64 characters, no duplicates, every code prefixed with {@code
 * institution-}, and every exception that declares one is a {@link DomainException} subclass.
 *
 * <p>PR B1 closed six of the module's thirteen codes: the four construction codes from {@link
 * InvalidInstitutionException} that were already reachable from production code in that PR
 * ({@code legalName} and {@code tradeName}), plus the two transition codes from {@link
 * InstitutionStateException}. PR B2 closed five more ({@code rtn}, {@code address}, {@code
 * locale} and {@code timezone}), for eleven total. This PR (C) closes the final two,
 * {@link InstitutionNotFoundException}'s {@code institution-not-found} and
 * {@link InstitutionInactiveException}'s {@code institution-inactive}, both raised by the {@code
 * application} layer's {@code ResolveCurrentInstitution} use case rather than by {@code
 * organization.domain} itself — hence their public, no-argument constructors instead of the
 * package-private factories the rest of the catalog uses (design.md, decision 6).
 */
class OrganizationErrorCodesTest {

    private static final Pattern KEBAB_CASE = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    private static final int MAX_CODE_LENGTH = 64;
    private static final String MODULE_PREFIX = "institution-";

    @Test
    void catalogHasExactlyTheThirteenCodesOfTheCompleteModule() {
        assertThat(allOrganizationErrorCodes()).containsExactlyInAnyOrder(
                "institution-legal-name-blank",
                "institution-legal-name-too-long",
                "institution-trade-name-blank",
                "institution-trade-name-too-long",
                "institution-rtn-invalid",
                "institution-address-blank",
                "institution-address-too-long",
                "institution-locale-invalid",
                "institution-timezone-invalid",
                "institution-already-active",
                "institution-already-inactive",
                "institution-not-found",
                "institution-inactive");
    }

    @Test
    void everyCodeIsUniqueAcrossTheModule() {
        List<String> codes = allOrganizationErrorCodes();

        assertThat(codes).doesNotHaveDuplicates();
    }

    @Test
    void everyCodeFollowsTheKebabCaseFormatAndMaximumLength() {
        for (String code : allOrganizationErrorCodes()) {
            assertThat(code).as("code '%s' must be kebab-case", code).matches(KEBAB_CASE);
            assertThat(code.length())
                    .as("code '%s' must be at most %d characters", code, MAX_CODE_LENGTH)
                    .isLessThanOrEqualTo(MAX_CODE_LENGTH);
        }
    }

    @Test
    void theKebabCasePatternRejectsAMalformedCode() {
        // Without this, the format check above could pass against a pattern that accepts anything.
        assertThat("Institution-Rtn-Invalid").doesNotMatch(KEBAB_CASE);
        assertThat("institution_rtn_invalid").doesNotMatch(KEBAB_CASE);
        assertThat("institution--rtn").doesNotMatch(KEBAB_CASE);
        assertThat("-institution-rtn").doesNotMatch(KEBAB_CASE);
    }

    @Test
    void everyCodeCarriesTheModulePrefix() {
        for (String code : allOrganizationErrorCodes()) {
            assertThat(code).as("code '%s' must start with '%s'", code, MODULE_PREFIX)
                    .startsWith(MODULE_PREFIX);
        }
    }

    @Test
    void everyExceptionThatDeclaresAnOrganizationCodeIsADomainException() {
        assertThat(InvalidInstitutionException.legalNameBlank()).isInstanceOf(DomainException.class);
        assertThat(InvalidInstitutionException.legalNameTooLong())
                .isInstanceOf(DomainException.class);
        assertThat(InvalidInstitutionException.tradeNameBlank()).isInstanceOf(DomainException.class);
        assertThat(InvalidInstitutionException.tradeNameTooLong())
                .isInstanceOf(DomainException.class);
        assertThat(InvalidInstitutionException.rtnInvalid()).isInstanceOf(DomainException.class);
        assertThat(InvalidInstitutionException.addressBlank()).isInstanceOf(DomainException.class);
        assertThat(InvalidInstitutionException.addressTooLong())
                .isInstanceOf(DomainException.class);
        assertThat(InvalidInstitutionException.localeInvalid()).isInstanceOf(DomainException.class);
        assertThat(InvalidInstitutionException.timezoneInvalid())
                .isInstanceOf(DomainException.class);
        assertThat(InstitutionStateException.alreadyActive()).isInstanceOf(DomainException.class);
        assertThat(InstitutionStateException.alreadyInactive()).isInstanceOf(DomainException.class);
        assertThat(new InstitutionNotFoundException()).isInstanceOf(DomainException.class);
        assertThat(new InstitutionInactiveException()).isInstanceOf(DomainException.class);
    }

    @Test
    void theTwoResolutionExceptionsAreConstructibleWithAPublicNoArgumentConstructor() {
        assertThat(new InstitutionNotFoundException().code())
                .isEqualTo(InstitutionNotFoundException.CODE);
        assertThat(new InstitutionInactiveException().code())
                .isEqualTo(InstitutionInactiveException.CODE);
    }

    private static List<String> allOrganizationErrorCodes() {
        return List.of(
                InvalidInstitutionException.LEGAL_NAME_BLANK,
                InvalidInstitutionException.LEGAL_NAME_TOO_LONG,
                InvalidInstitutionException.TRADE_NAME_BLANK,
                InvalidInstitutionException.TRADE_NAME_TOO_LONG,
                InvalidInstitutionException.RTN_INVALID,
                InvalidInstitutionException.ADDRESS_BLANK,
                InvalidInstitutionException.ADDRESS_TOO_LONG,
                InvalidInstitutionException.LOCALE_INVALID,
                InvalidInstitutionException.TIMEZONE_INVALID,
                InstitutionStateException.ALREADY_ACTIVE,
                InstitutionStateException.ALREADY_INACTIVE,
                InstitutionNotFoundException.CODE,
                InstitutionInactiveException.CODE);
    }
}
