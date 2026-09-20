package com.confia.organization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Activation, deactivation and identity equality of {@link Institution}
 * (specs/organization/spec.md, requirement "Activación y desactivación de una institución", all
 * four scenarios, and requirement "Jerarquía de errores de dominio del módulo `organization`",
 * partial: {@link DomainException} inheritance).
 *
 * <p>Transitions are deliberately not idempotent (design.md, decision 5): a double state change is
 * a caller defect, not a valid operation.
 */
class InstitutionLifecycleTest {

    private static final String VALID_RTN = "08019012345678";
    private static final String VALID_ADDRESS = "Colonia Palmira, Tegucigalpa";
    private static final CurrencyCode VALID_CURRENCY = CurrencyCode.HNL;
    private static final Locale VALID_LOCALE = Locale.forLanguageTag("es-HN");
    private static final ZoneId VALID_TIMEZONE = ZoneId.of("America/Tegucigalpa");

    @Test
    void deactivatingAnActiveInstitutionSucceedsWithoutModifyingOtherAttributes() {
        Institution institution = anInstitution();

        institution.deactivate();

        assertThat(institution.isActive()).isFalse();
        assertThat(institution.legalName()).isEqualTo("Instituto San Marcos");
        assertThat(institution.tradeName()).isEqualTo("Colegio San Marcos");
    }

    @Test
    void reactivatingAnInactiveInstitutionSucceeds() {
        Institution institution = anInstitution();
        institution.deactivate();

        institution.activate();

        assertThat(institution.isActive()).isTrue();
    }

    @Test
    void activatingAnAlreadyActiveInstitutionFailsWithoutModifyingState() {
        Institution institution = anInstitution();

        assertThatThrownBy(institution::activate)
                .isInstanceOf(InstitutionStateException.class)
                .extracting(exception -> ((InstitutionStateException) exception).code())
                .isEqualTo(InstitutionStateException.ALREADY_ACTIVE);
        assertThat(institution.isActive()).isTrue();
    }

    @Test
    void deactivatingAnAlreadyInactiveInstitutionFailsWithoutModifyingState() {
        Institution institution = anInstitution();
        institution.deactivate();

        assertThatThrownBy(institution::deactivate)
                .isInstanceOf(InstitutionStateException.class)
                .extracting(exception -> ((InstitutionStateException) exception).code())
                .isEqualTo(InstitutionStateException.ALREADY_INACTIVE);
        assertThat(institution.isActive()).isFalse();
    }

    @Test
    void twoInstitutionsWithTheSameIdAreEqualRegardlessOfOtherAttributes() {
        InstitutionId id = anId();
        Institution first = Institution.create(id, "Instituto San Marcos", "Colegio San Marcos",
                VALID_RTN, VALID_ADDRESS, VALID_CURRENCY, VALID_LOCALE, VALID_TIMEZONE);
        Institution second = Institution.create(id, "Instituto Diferente", null, VALID_RTN,
                VALID_ADDRESS, VALID_CURRENCY, VALID_LOCALE, VALID_TIMEZONE);

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(first.hashCode()).isEqualTo(id.hashCode());
    }

    @Test
    void anInstitutionIsEqualToItself() {
        Institution institution = anInstitution();

        assertThat(institution).isEqualTo(institution);
    }

    @Test
    void anInstitutionIsNeverEqualToANonInstitutionOrToNull() {
        Institution institution = anInstitution();

        assertThat(institution).isNotEqualTo("not an institution");
        assertThat(institution).isNotEqualTo(null);
    }

    @Test
    void twoInstitutionsWithDifferentIdsAreNeverEqualEvenWithIdenticalOtherAttributes() {
        Institution first = anInstitution();
        Institution second = anInstitution();

        assertThat(first).isNotEqualTo(second);
    }

    private static Institution anInstitution() {
        return Institution.create(anId(), "Instituto San Marcos", "Colegio San Marcos", VALID_RTN,
                VALID_ADDRESS, VALID_CURRENCY, VALID_LOCALE, VALID_TIMEZONE);
    }

    private static InstitutionId anId() {
        return new InstitutionId(UUID.randomUUID());
    }
}
