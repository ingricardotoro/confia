package com.confia.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PriceListTest {

    private static final Instant T0 = Instant.parse("2026-11-16T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-17T12:00:00Z");
    private static final UUID YEAR = UUID.randomUUID();

    private static PriceList institutionList() {
        return PriceList.create(UUID.randomUUID(), YEAR, " 2027 — Institución ", PriceListScope.INSTITUTION, null,
                T0);
    }

    @Test
    void aListIsBornActiveAtVersionZeroWithItsNameStripped() {
        PriceList list = institutionList();

        assertThat(list.name()).isEqualTo("2027 — Institución");
        assertThat(list.status()).isEqualTo(PriceListStatus.ACTIVE);
        assertThat(list.version()).isZero();
        assertThat(list.scopeRefId()).isNull();
        assertThat(list.createdAt()).isEqualTo(T0);
        assertThat(list.updatedAt()).isEqualTo(T0);
    }

    @Test
    void theReferenceOfTheScopeIsAbsentForTheInstitutionAndRequiredForTheOtherTwo() {
        UUID ref = UUID.randomUUID();

        assertThat(PriceList.create(UUID.randomUUID(), YEAR, "Bachillerato", PriceListScope.MODALITY, ref, T0)
                .scopeRefId()).isEqualTo(ref);
        assertThat(PriceList.create(UUID.randomUUID(), YEAR, "Décimo", PriceListScope.GRADE, ref, T0).scope())
                .isEqualTo(PriceListScope.GRADE);
        assertThatThrownBy(() -> PriceList.create(UUID.randomUUID(), YEAR, "X", PriceListScope.MODALITY, null, T0))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> PriceList.create(UUID.randomUUID(), YEAR, "X", PriceListScope.GRADE, null, T0))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> PriceList.create(UUID.randomUUID(), YEAR, "X", PriceListScope.INSTITUTION, ref,
                T0)).isInstanceOf(InvalidCatalogValueException.class);
    }

    @Test
    void aListNeedsAYearAScopeAndAValidName() {
        assertThatThrownBy(() -> PriceList.create(UUID.randomUUID(), null, "X", PriceListScope.INSTITUTION, null, T0))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> PriceList.create(UUID.randomUUID(), YEAR, "X", null, null, T0))
                .isInstanceOf(InvalidCatalogValueException.class);
        for (String name : new String[] {"", "   ", "N".repeat(121), "Con\nsalto", "Con​cero"}) {
            assertThatThrownBy(() -> PriceList.create(UUID.randomUUID(), YEAR, name, PriceListScope.INSTITUTION, null,
                    T0)).isInstanceOf(InvalidCatalogValueException.class);
        }
        assertThat(PriceList.create(UUID.randomUUID(), YEAR, "N".repeat(120), PriceListScope.INSTITUTION, null, T0)
                .name()).hasSize(120);
    }

    @Test
    void aNegativeVersionOrAMissingStatusIsRefused() {
        assertThatThrownBy(() -> new PriceList(UUID.randomUUID(), YEAR, "X", PriceListScope.INSTITUTION, null,
                PriceListStatus.ACTIVE, -1, T0, T0)).isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> new PriceList(UUID.randomUUID(), YEAR, "X", PriceListScope.INSTITUTION, null, null,
                0, T0, T0)).isInstanceOf(InvalidCatalogValueException.class);
    }

    @Test
    void anEditChangesTheNameAndTheStatusAdvancesTheVersionAndKeepsYearScopeAndCreation() {
        PriceList before = institutionList();

        PriceList after = before.edit(0, "Nuevo nombre", PriceListStatus.ARCHIVED, T1);

        assertThat(after.name()).isEqualTo("Nuevo nombre");
        assertThat(after.status()).isEqualTo(PriceListStatus.ARCHIVED);
        assertThat(after.version()).isEqualTo(1);
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.academicYearId()).isEqualTo(YEAR);
        assertThat(after.scope()).isEqualTo(PriceListScope.INSTITUTION);
        assertThat(after.createdAt()).isEqualTo(T0);
        assertThat(after.updatedAt()).isEqualTo(T1);
    }

    @Test
    void anEditWithAnotherVersionThanTheCurrentIsStaleAndOneWithABadNameIsInvalid() {
        PriceList current = institutionList().edit(0, "Uno", PriceListStatus.ACTIVE, T1);

        assertThatThrownBy(() -> current.edit(0, "Dos", PriceListStatus.ACTIVE, T1))
                .isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> current.edit(2, "Dos", PriceListStatus.ACTIVE, T1))
                .isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> current.edit(1, " ", PriceListStatus.ACTIVE, T1))
                .isInstanceOf(InvalidCatalogValueException.class);
    }

    @Test
    void anArchivedListTakesNoPriceAndAnActiveOneDoes() {
        PriceList active = institutionList();
        PriceList archived = active.edit(0, active.name(), PriceListStatus.ARCHIVED, T1);

        active.requireAcceptsPrices();
        assertThatThrownBy(archived::requireAcceptsPrices).isInstanceOf(PriceListArchivedException.class);
        archived.edit(1, archived.name(), PriceListStatus.ACTIVE, T1).requireAcceptsPrices();
    }

    @Test
    void theScopeAndTheStatusAreClosedSetsReadWithTheirCapitals() {
        assertThat(PriceListScope.values()).extracting(Enum::name).containsExactly("INSTITUTION", "MODALITY", "GRADE");
        assertThat(PriceListStatus.values()).extracting(Enum::name).containsExactly("ACTIVE", "ARCHIVED");
        for (String bad : new String[] {"institution", "ALL", "", " "}) {
            assertThatThrownBy(() -> PriceListScope.parse(bad)).isInstanceOf(InvalidCatalogValueException.class);
        }
        assertThatThrownBy(() -> PriceListScope.parse(null)).isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> PriceListStatus.parse("active")).isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> PriceListStatus.parse(null)).isInstanceOf(InvalidCatalogValueException.class);
        assertThat(PriceListStatus.parse("ARCHIVED")).isEqualTo(PriceListStatus.ARCHIVED);
        assertThat(PriceListScope.parse("GRADE")).isEqualTo(PriceListScope.GRADE);
    }

    @Test
    void theErrorCodesAreTheOnesTheEdgePublishes() {
        assertThat(PriceValidityOverlapException.CODE).isEqualTo("price-validity-overlap");
        assertThat(PriceListArchivedException.CODE).isEqualTo("price-list-archived");
        assertThat(StaleVersionException.CODE).isEqualTo("stale-version");
        assertThat(InactiveReferenceException.CODE).isEqualTo("inactive-reference");
    }
}
