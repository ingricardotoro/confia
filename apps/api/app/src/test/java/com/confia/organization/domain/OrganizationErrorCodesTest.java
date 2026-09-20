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
 * <p>This PR (B1) closes six of the module's eventual thirteen codes: the four construction codes
 * from {@link InvalidInstitutionException} that are already reachable from production code in
 * this PR ({@code legalName} and {@code tradeName}), plus the two transition codes from {@link
 * InstitutionStateException}. {@code rtn}, {@code address}, {@code locale} and {@code timezone}
 * land in PR B2 (five more codes); {@code institution-not-found} and {@code institution-inactive}
 * land in PR C (the final two), each PR extending this same catalog.
 */
class OrganizationErrorCodesTest {

    private static final Pattern KEBAB_CASE = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    private static final int MAX_CODE_LENGTH = 64;
    private static final String MODULE_PREFIX = "institution-";

    @Test
    void catalogHasExactlyTheSixCodesClosedInThisPullRequest() {
        assertThat(allOrganizationErrorCodes()).containsExactlyInAnyOrder(
                "institution-legal-name-blank",
                "institution-legal-name-too-long",
                "institution-trade-name-blank",
                "institution-trade-name-too-long",
                "institution-already-active",
                "institution-already-inactive");
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
        assertThat(InstitutionStateException.alreadyActive()).isInstanceOf(DomainException.class);
        assertThat(InstitutionStateException.alreadyInactive()).isInstanceOf(DomainException.class);
    }

    private static List<String> allOrganizationErrorCodes() {
        return List.of(
                InvalidInstitutionException.LEGAL_NAME_BLANK,
                InvalidInstitutionException.LEGAL_NAME_TOO_LONG,
                InvalidInstitutionException.TRADE_NAME_BLANK,
                InvalidInstitutionException.TRADE_NAME_TOO_LONG,
                InstitutionStateException.ALREADY_ACTIVE,
                InstitutionStateException.ALREADY_INACTIVE);
    }
}
