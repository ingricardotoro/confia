package com.confia.organization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Construction of {@link Institution} and its identity attributes (specs/organization/spec.md,
 * requirement "Construcción de `Institution` y sus atributos de identidad obligatorios", partial:
 * {@code locale} and {@code timezone} land later in PR B2) and requirement "Nombre comercial
 * opcional".
 *
 * <p>{@code tradeName}'s own blank/too-long validation is implemented already in this PR (ahead of
 * its nominal task 3.6 in PR B2) so that {@link OrganizationErrorCodesTest}'s six-code catalog,
 * closed in this same PR, has every code genuinely reachable from production code instead of an
 * unused factory.
 */
class InstitutionCreationTest {

    private static final String VALID_RTN = "08019012345678";
    private static final String VALID_ADDRESS = "Colonia Palmira, Tegucigalpa";
    private static final CurrencyCode VALID_CURRENCY = CurrencyCode.HNL;

    @Test
    void happyPathConstructionWithAllValidAttributesSucceeds() {
        InstitutionId id = anId();

        Institution institution = Institution.create(id, "Instituto San Marcos",
                "Colegio San Marcos", VALID_RTN, VALID_ADDRESS, VALID_CURRENCY);

        assertThat(institution.id()).isEqualTo(id);
        assertThat(institution.legalName()).isEqualTo("Instituto San Marcos");
        assertThat(institution.tradeName()).isEqualTo("Colegio San Marcos");
        assertThat(institution.rtn()).isEqualTo(VALID_RTN);
        assertThat(institution.address()).isEqualTo(VALID_ADDRESS);
        assertThat(institution.defaultCurrency()).isEqualTo(VALID_CURRENCY);
        assertThat(institution.isActive()).isTrue();
    }

    @Test
    void constructionStripsLeadingAndTrailingWhitespaceFromNames() {
        Institution institution = Institution.create(anId(), "  Instituto San Marcos  ",
                "  Colegio San Marcos  ", VALID_RTN, VALID_ADDRESS, VALID_CURRENCY);

        assertThat(institution.legalName()).isEqualTo("Instituto San Marcos");
        assertThat(institution.tradeName()).isEqualTo("Colegio San Marcos");
    }

    @Test
    void rejectsALegalNameProvidedAsBlank() {
        assertThatThrownBy(() -> Institution.create(anId(), "   ", "Colegio San Marcos",
                VALID_RTN, VALID_ADDRESS, VALID_CURRENCY))
                .isInstanceOf(InvalidInstitutionException.class)
                .extracting(exception -> ((InvalidInstitutionException) exception).code())
                .isEqualTo(InvalidInstitutionException.LEGAL_NAME_BLANK);
    }

    @Test
    void acceptsALegalNameOfExactlyTwoHundredCodePoints() {
        String legalName = "A".repeat(200);

        Institution institution = Institution.create(anId(), legalName, "Colegio San Marcos",
                VALID_RTN, VALID_ADDRESS, VALID_CURRENCY);

        assertThat(institution.legalName()).isEqualTo(legalName);
    }

    @Test
    void rejectsALegalNameOfTwoHundredAndOneCodePoints() {
        String legalName = "A".repeat(201);

        assertThatThrownBy(() -> Institution.create(anId(), legalName, "Colegio San Marcos",
                VALID_RTN, VALID_ADDRESS, VALID_CURRENCY))
                .isInstanceOf(InvalidInstitutionException.class)
                .extracting(exception -> ((InvalidInstitutionException) exception).code())
                .isEqualTo(InvalidInstitutionException.LEGAL_NAME_TOO_LONG);
    }

    @Test
    void rejectsANullId() {
        assertThatThrownBy(() -> Institution.create(null, "Instituto San Marcos",
                "Colegio San Marcos", VALID_RTN, VALID_ADDRESS, VALID_CURRENCY))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANullLegalName() {
        assertThatThrownBy(() -> Institution.create(anId(), null, "Colegio San Marcos", VALID_RTN,
                VALID_ADDRESS, VALID_CURRENCY))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructionSucceedsWithoutATradeName() {
        Institution institution = Institution.create(anId(), "Instituto San Marcos", null,
                VALID_RTN, VALID_ADDRESS, VALID_CURRENCY);

        assertThat(institution.tradeName()).isNull();
    }

    @Test
    void rejectsATradeNameProvidedAsBlank() {
        assertThatThrownBy(() -> Institution.create(anId(), "Instituto San Marcos", "   ",
                VALID_RTN, VALID_ADDRESS, VALID_CURRENCY))
                .isInstanceOf(InvalidInstitutionException.class)
                .extracting(exception -> ((InvalidInstitutionException) exception).code())
                .isEqualTo(InvalidInstitutionException.TRADE_NAME_BLANK);
    }

    @Test
    void acceptsATradeNameOfExactlyTwoHundredCodePoints() {
        String tradeName = "B".repeat(200);

        Institution institution = Institution.create(anId(), "Instituto San Marcos", tradeName,
                VALID_RTN, VALID_ADDRESS, VALID_CURRENCY);

        assertThat(institution.tradeName()).isEqualTo(tradeName);
    }

    @Test
    void rejectsATradeNameOfTwoHundredAndOneCodePoints() {
        String tradeName = "B".repeat(201);

        assertThatThrownBy(() -> Institution.create(anId(), "Instituto San Marcos", tradeName,
                VALID_RTN, VALID_ADDRESS, VALID_CURRENCY))
                .isInstanceOf(InvalidInstitutionException.class)
                .extracting(exception -> ((InvalidInstitutionException) exception).code())
                .isEqualTo(InvalidInstitutionException.TRADE_NAME_TOO_LONG);
    }

    @Test
    void rejectsAnEmptyRtn() {
        assertThatThrownBy(() -> Institution.create(anId(), "Instituto San Marcos",
                "Colegio San Marcos", "", VALID_ADDRESS, VALID_CURRENCY))
                .isInstanceOf(InvalidInstitutionException.class)
                .extracting(exception -> ((InvalidInstitutionException) exception).code())
                .isEqualTo(InvalidInstitutionException.RTN_INVALID);
    }

    @Test
    void rejectsARtnWithNonDigitCharactersOrTooLong() {
        assertThatThrownBy(() -> Institution.create(anId(), "Instituto San Marcos",
                "Colegio San Marcos", "0801-1990-12345", VALID_ADDRESS, VALID_CURRENCY))
                .isInstanceOf(InvalidInstitutionException.class)
                .extracting(exception -> ((InvalidInstitutionException) exception).code())
                .isEqualTo(InvalidInstitutionException.RTN_INVALID);

        String twentyOneDigits = "1".repeat(21);
        assertThatThrownBy(() -> Institution.create(anId(), "Instituto San Marcos",
                "Colegio San Marcos", twentyOneDigits, VALID_ADDRESS, VALID_CURRENCY))
                .isInstanceOf(InvalidInstitutionException.class)
                .extracting(exception -> ((InvalidInstitutionException) exception).code())
                .isEqualTo(InvalidInstitutionException.RTN_INVALID);

        String fourteenDigits = "1".repeat(14);
        Institution institution = Institution.create(anId(), "Instituto San Marcos",
                "Colegio San Marcos", fourteenDigits, VALID_ADDRESS, VALID_CURRENCY);
        assertThat(institution.rtn()).isEqualTo(fourteenDigits);
    }

    @Test
    void acceptsRtnOfOneAndTwentyDigits() {
        String oneDigit = "1";
        String twentyDigits = "1".repeat(20);

        Institution shortest = Institution.create(anId(), "Instituto San Marcos",
                "Colegio San Marcos", oneDigit, VALID_ADDRESS, VALID_CURRENCY);
        Institution longest = Institution.create(anId(), "Instituto San Marcos",
                "Colegio San Marcos", twentyDigits, VALID_ADDRESS, VALID_CURRENCY);

        assertThat(shortest.rtn()).isEqualTo(oneDigit);
        assertThat(longest.rtn()).isEqualTo(twentyDigits);
    }

    @Test
    void rejectsANullRtn() {
        assertThatThrownBy(() -> Institution.create(anId(), "Instituto San Marcos",
                "Colegio San Marcos", null, VALID_ADDRESS, VALID_CURRENCY))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsAnEmptyAddress() {
        assertThatThrownBy(() -> Institution.create(anId(), "Instituto San Marcos",
                "Colegio San Marcos", VALID_RTN, "", VALID_CURRENCY))
                .isInstanceOf(InvalidInstitutionException.class)
                .extracting(exception -> ((InvalidInstitutionException) exception).code())
                .isEqualTo(InvalidInstitutionException.ADDRESS_BLANK);
    }

    @Test
    void rejectsAnAddressMadeOnlyOfWhitespace() {
        assertThatThrownBy(() -> Institution.create(anId(), "Instituto San Marcos",
                "Colegio San Marcos", VALID_RTN, "   ", VALID_CURRENCY))
                .isInstanceOf(InvalidInstitutionException.class)
                .extracting(exception -> ((InvalidInstitutionException) exception).code())
                .isEqualTo(InvalidInstitutionException.ADDRESS_BLANK);
    }

    @Test
    void acceptsAnAddressOfExactlyFiveHundredCodePoints() {
        String address = "A".repeat(500);

        Institution institution = Institution.create(anId(), "Instituto San Marcos",
                "Colegio San Marcos", VALID_RTN, address, VALID_CURRENCY);

        assertThat(institution.address()).isEqualTo(address);
    }

    @Test
    void rejectsAnAddressOfFiveHundredAndOneCodePoints() {
        String address = "A".repeat(501);

        assertThatThrownBy(() -> Institution.create(anId(), "Instituto San Marcos",
                "Colegio San Marcos", VALID_RTN, address, VALID_CURRENCY))
                .isInstanceOf(InvalidInstitutionException.class)
                .extracting(exception -> ((InvalidInstitutionException) exception).code())
                .isEqualTo(InvalidInstitutionException.ADDRESS_TOO_LONG);
    }

    @Test
    void rejectsANullAddress() {
        assertThatThrownBy(() -> Institution.create(anId(), "Instituto San Marcos",
                "Colegio San Marcos", VALID_RTN, null, VALID_CURRENCY))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANullDefaultCurrency() {
        assertThatThrownBy(() -> Institution.create(anId(), "Instituto San Marcos",
                "Colegio San Marcos", VALID_RTN, VALID_ADDRESS, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void acceptsEveryEnabledCurrency() {
        Institution hnl = Institution.create(anId(), "Instituto San Marcos",
                "Colegio San Marcos", VALID_RTN, VALID_ADDRESS, CurrencyCode.HNL);
        Institution usd = Institution.create(anId(), "Instituto San Marcos",
                "Colegio San Marcos", VALID_RTN, VALID_ADDRESS, CurrencyCode.USD);

        assertThat(hnl.defaultCurrency()).isEqualTo(CurrencyCode.HNL);
        assertThat(usd.defaultCurrency()).isEqualTo(CurrencyCode.USD);
    }

    static InstitutionId anId() {
        return new InstitutionId(UUID.randomUUID());
    }
}
