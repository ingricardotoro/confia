package com.confia.organization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Construction of {@link Institution} and its identity attributes (specs/organization/spec.md,
 * requirement "Construcción de `Institution` y sus atributos de identidad obligatorios", partial:
 * {@code rtn}, {@code address}, {@code defaultCurrency}, {@code locale} and {@code timezone} land
 * in PR B2) and requirement "Nombre comercial opcional".
 *
 * <p>{@code tradeName}'s own blank/too-long validation is implemented already in this PR (ahead of
 * its nominal task 3.6 in PR B2) so that {@link OrganizationErrorCodesTest}'s six-code catalog,
 * closed in this same PR, has every code genuinely reachable from production code instead of an
 * unused factory.
 */
class InstitutionCreationTest {

    @Test
    void happyPathConstructionWithAllValidAttributesSucceeds() {
        InstitutionId id = anId();

        Institution institution = Institution.create(id, "Instituto San Marcos", "Colegio San Marcos");

        assertThat(institution.id()).isEqualTo(id);
        assertThat(institution.legalName()).isEqualTo("Instituto San Marcos");
        assertThat(institution.tradeName()).isEqualTo("Colegio San Marcos");
        assertThat(institution.isActive()).isTrue();
    }

    @Test
    void constructionStripsLeadingAndTrailingWhitespaceFromNames() {
        Institution institution =
                Institution.create(anId(), "  Instituto San Marcos  ", "  Colegio San Marcos  ");

        assertThat(institution.legalName()).isEqualTo("Instituto San Marcos");
        assertThat(institution.tradeName()).isEqualTo("Colegio San Marcos");
    }

    @Test
    void rejectsALegalNameProvidedAsBlank() {
        assertThatThrownBy(() -> Institution.create(anId(), "   ", "Colegio San Marcos"))
                .isInstanceOf(InvalidInstitutionException.class)
                .extracting(exception -> ((InvalidInstitutionException) exception).code())
                .isEqualTo(InvalidInstitutionException.LEGAL_NAME_BLANK);
    }

    @Test
    void acceptsALegalNameOfExactlyTwoHundredCodePoints() {
        String legalName = "A".repeat(200);

        Institution institution = Institution.create(anId(), legalName, "Colegio San Marcos");

        assertThat(institution.legalName()).isEqualTo(legalName);
    }

    @Test
    void rejectsALegalNameOfTwoHundredAndOneCodePoints() {
        String legalName = "A".repeat(201);

        assertThatThrownBy(() -> Institution.create(anId(), legalName, "Colegio San Marcos"))
                .isInstanceOf(InvalidInstitutionException.class)
                .extracting(exception -> ((InvalidInstitutionException) exception).code())
                .isEqualTo(InvalidInstitutionException.LEGAL_NAME_TOO_LONG);
    }

    @Test
    void rejectsANullId() {
        assertThatThrownBy(
                () -> Institution.create(null, "Instituto San Marcos", "Colegio San Marcos"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANullLegalName() {
        assertThatThrownBy(() -> Institution.create(anId(), null, "Colegio San Marcos"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructionSucceedsWithoutATradeName() {
        Institution institution = Institution.create(anId(), "Instituto San Marcos", null);

        assertThat(institution.tradeName()).isNull();
    }

    @Test
    void rejectsATradeNameProvidedAsBlank() {
        assertThatThrownBy(() -> Institution.create(anId(), "Instituto San Marcos", "   "))
                .isInstanceOf(InvalidInstitutionException.class)
                .extracting(exception -> ((InvalidInstitutionException) exception).code())
                .isEqualTo(InvalidInstitutionException.TRADE_NAME_BLANK);
    }

    @Test
    void acceptsATradeNameOfExactlyTwoHundredCodePoints() {
        String tradeName = "B".repeat(200);

        Institution institution = Institution.create(anId(), "Instituto San Marcos", tradeName);

        assertThat(institution.tradeName()).isEqualTo(tradeName);
    }

    @Test
    void rejectsATradeNameOfTwoHundredAndOneCodePoints() {
        String tradeName = "B".repeat(201);

        assertThatThrownBy(() -> Institution.create(anId(), "Instituto San Marcos", tradeName))
                .isInstanceOf(InvalidInstitutionException.class)
                .extracting(exception -> ((InvalidInstitutionException) exception).code())
                .isEqualTo(InvalidInstitutionException.TRADE_NAME_TOO_LONG);
    }

    private static InstitutionId anId() {
        return new InstitutionId(UUID.randomUUID());
    }
}
