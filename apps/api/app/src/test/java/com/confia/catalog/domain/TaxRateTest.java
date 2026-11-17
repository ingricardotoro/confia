package com.confia.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaxRateTest {

    private static final Instant T0 = Instant.parse("2026-01-10T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-02-10T12:00:00Z");

    private static TaxRate rate() {
        return TaxRate.create(UUID.randomUUID(), "ISV", " Impuesto sobre ventas ", T0);
    }

    @Test
    void aRateStripsItsNameKeepsItsCodeAndIsBornActiveAtVersionZero() {
        TaxRate created = rate();

        assertThat(created.code()).isEqualTo("ISV");
        assertThat(created.name()).isEqualTo("Impuesto sobre ventas");
        assertThat(created.active()).isTrue();
        assertThat(created.version()).isZero();
        assertThat(created.createdAt()).isEqualTo(T0);
        assertThat(created.updatedAt()).isEqualTo(T0);
    }

    @Test
    void aCodeOrNameThatIsBlankMissingTooLongOrOutsideTheWhitelistIsRefused() {
        UUID id = UUID.randomUUID();

        for (String code : new String[] {"  ", null, "C".repeat(31), "-ISV", "I SV", "ISV\n", "=ISV", "ÍSV", "IS​V"}) {
            assertThatThrownBy(() -> TaxRate.create(id, code, "Nombre", T0)).as("code %s", code)
                    .isInstanceOf(InvalidCatalogValueException.class);
        }
        for (String name : new String[] {"", " ", null, "N".repeat(121), "con\nsalto", "con‮bidi"}) {
            assertThatThrownBy(() -> TaxRate.create(id, "ISV", name, T0)).as("name %s", name)
                    .isInstanceOf(InvalidCatalogValueException.class);
        }
        assertThat(TaxRate.create(id, "C".repeat(30), "N".repeat(120), T0).code()).hasSize(30);
        assertThat(TaxRate.create(id, "i-s_v9", "N", T0).code()).isEqualTo("i-s_v9");
    }

    @Test
    void anEditReplacesTheNameAndTheFlagAdvancesTheVersionAndKeepsTheCodeAndTheCreation() {
        TaxRate before = rate();

        TaxRate edited = before.edit(0, "Otro nombre", false, T1);

        assertThat(edited.id()).isEqualTo(before.id());
        assertThat(edited.code()).isEqualTo("ISV");
        assertThat(edited.name()).isEqualTo("Otro nombre");
        assertThat(edited.active()).isFalse();
        assertThat(edited.version()).isEqualTo(1);
        assertThat(edited.createdAt()).isEqualTo(T0);
        assertThat(edited.updatedAt()).isEqualTo(T1);
        assertThat(edited.edit(1, "Otro nombre", true, T1).version()).isEqualTo(2);
    }

    @Test
    void anEditThatNamesAnotherVersionIsStaleAndABlankNameIsRefused() {
        TaxRate current = rate().edit(0, "Uno", true, T1);

        assertThatThrownBy(() -> current.edit(0, "X", true, T1)).isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> current.edit(2, "X", true, T1)).isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> current.edit(-1, "X", true, T1)).isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> current.edit(1, " ", true, T1)).isInstanceOf(InvalidCatalogValueException.class);
    }

    @Test
    void anInactiveRateIsAnInactiveReferenceAndAnActiveOneIsNot() {
        TaxRate active = rate();
        TaxRate inactive = active.edit(0, active.name(), false, T1);

        active.requireActive();
        assertThatThrownBy(inactive::requireActive).isInstanceOf(InactiveReferenceException.class);
    }

    @Test
    void aNegativeVersionIsRefused() {
        assertThatThrownBy(() -> new TaxRate(UUID.randomUUID(), "ISV", "Nombre", true, -1, T0, T0))
                .isInstanceOf(InvalidCatalogValueException.class);
    }

    @Test
    void theErrorCodesAreTheOnesTheEdgePublishes() {
        assertThat(new DuplicateTaxRateCodeException().code()).isEqualTo("duplicate-tax-rate-code");
        assertThat(new TaxRatePeriodOverlapException().code()).isEqualTo("tax-rate-period-overlap");
        assertThat(new InactiveReferenceException().code()).isEqualTo("inactive-reference");
    }
}
