package com.confia.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentConceptTest {

    private static final Instant T0 = Instant.parse("2026-01-10T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-02-10T12:00:00Z");
    private static final UUID RATE = UUID.randomUUID();

    private static PaymentConcept concept() {
        return PaymentConcept.create(UUID.randomUUID(), "COLEG", " Colegiatura ", PaymentCategory.TUITION,
                true, true, RATE, T0);
    }

    @Test
    void aConceptStripsItsNameKeepsItsCodeAndIsBornActive() {
        PaymentConcept created = concept();

        assertThat(created.code()).isEqualTo("COLEG");
        assertThat(created.name()).isEqualTo("Colegiatura");
        assertThat(created.category()).isEqualTo(PaymentCategory.TUITION);
        assertThat(created.taxable()).isTrue();
        assertThat(created.recurring()).isTrue();
        assertThat(created.active()).isTrue();
        assertThat(created.createdAt()).isEqualTo(T0);
        assertThat(created.updatedAt()).isEqualTo(T0);
    }

    @Test
    void aCodeOrNameThatIsBlankMissingOrTooLongIsRefused() {
        UUID id = UUID.randomUUID();
        PaymentCategory category = PaymentCategory.OTHER;

        assertThatThrownBy(() -> PaymentConcept.create(id, "  ", "Nombre", category, false, false, null, T0))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> PaymentConcept.create(id, null, "Nombre", category, false, false, null, T0))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> PaymentConcept.create(id, "C".repeat(31), "Nombre", category, false, false, null, T0))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> PaymentConcept.create(id, "COD", "", category, false, false, null, T0))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> PaymentConcept.create(id, "COD", null, category, false, false, null, T0))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> PaymentConcept.create(id, "COD", "N".repeat(121), category, false, false, null, T0))
                .isInstanceOf(InvalidCatalogValueException.class);
    }

    @Test
    void aCodeIsAWhitelistOfLettersDigitsUnderscoreAndHyphenStartingWithALetterOrDigit() {
        UUID id = UUID.randomUUID();
        for (String code : new String[] {"COLEG", "a", "9", "A-1_b", "0".repeat(30)}) {
            assertThat(PaymentConcept.create(id, code, "N", PaymentCategory.OTHER, false, false, null, T0).code())
                    .isEqualTo(code);
        }
        for (String code : new String[] {" COLEG", "COLEG ", "CO LEG", "CO​LEG", "‮GELOC", "COLEG\n",
                "CO\nLEG", "=SUM(A1)", "-COLEG", "_COLEG", "+1", "@x", "Matrícula", "Ñandú", "C.O",
                "C/O", "C\u0000O", "​COLEG"}) {
            assertThatThrownBy(() -> PaymentConcept.create(id, code, "N", PaymentCategory.OTHER, false, false, null, T0))
                    .as("code %s", code.replace("\n", "\\n")).isInstanceOf(InvalidCatalogValueException.class);
        }
    }

    @Test
    void aNameWithControlOrFormatCharactersIsRefusedAndOtherUnicodeIsKept() {
        UUID id = UUID.randomUUID();
        for (String name : new String[] {"Co\nlegiatura", "Co\tlegiatura", "Co​legiatura", "‮atarutaigeloC",
                "Co\u0000l", "Co­l", "Co⁦l"}) {
            assertThatThrownBy(() -> PaymentConcept.create(id, "COD", name, PaymentCategory.OTHER, false, false, null, T0))
                    .as("name %s", name.replace("\n", "\\n")).isInstanceOf(InvalidCatalogValueException.class);
        }
        assertThat(PaymentConcept.create(id, "COD", "Matrícula año 2026 (Niños)",
                PaymentCategory.OTHER, false, false, null, T0).name()).isEqualTo("Matrícula año 2026 (Niños)");
    }

    @Test
    void anEditRefusesANameWithControlCharacters() {
        PaymentConcept before = concept();

        assertThatThrownBy(() -> before.edit(0, "Nueva\nlinea", PaymentCategory.TUITION, true, true, true, RATE, T1))
                .isInstanceOf(InvalidCatalogValueException.class);
    }

    @Test
    void theLongestCodeAndNameAreAccepted() {
        PaymentConcept created = PaymentConcept.create(UUID.randomUUID(), "C".repeat(30), "N".repeat(120),
                PaymentCategory.FINE, false, false, null, T0);

        assertThat(created.code()).hasSize(30);
        assertThat(created.name()).hasSize(120);
    }

    @Test
    void aNegativeVersionIsRefused() {
        assertThatThrownBy(() -> new PaymentConcept(UUID.randomUUID(), "COD", "Nombre", PaymentCategory.OTHER, false,
                false, true, null, -1, T0, T0)).isInstanceOf(InvalidCatalogValueException.class);
    }

    @Test
    void aConceptWithoutACategoryIsRefused() {
        assertThatThrownBy(() -> PaymentConcept.create(UUID.randomUUID(), "COD", "Nombre", null, false, false, null, T0))
                .isInstanceOf(InvalidCatalogValueException.class);
    }

    @Test
    void theCategoryIsAClosedSetOfSixAndAnythingElseIsRefused() {
        assertThat(PaymentCategory.values()).extracting(Enum::name).containsExactly("TUITION", "ENROLLMENT",
                "TRANSPORT", "MATERIAL", "FINE", "OTHER");
        for (PaymentCategory category : PaymentCategory.values()) {
            assertThat(PaymentCategory.parse(category.name())).isEqualTo(category);
        }
        assertThatThrownBy(() -> PaymentCategory.parse("tuition")).isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> PaymentCategory.parse("OTHERS")).isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> PaymentCategory.parse("")).isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> PaymentCategory.parse(null)).isInstanceOf(InvalidCatalogValueException.class);
    }

    @Test
    void anEditKeepsTheCodeTheIdentifierAndTheCreationInstantAndCanDeactivate() {
        PaymentConcept before = concept();

        PaymentConcept edited = before.edit(0, "Mensualidad", PaymentCategory.OTHER, false, false, false, null, T1);

        assertThat(edited.id()).isEqualTo(before.id());
        assertThat(edited.code()).isEqualTo("COLEG");
        assertThat(edited.name()).isEqualTo("Mensualidad");
        assertThat(edited.category()).isEqualTo(PaymentCategory.OTHER);
        assertThat(edited.taxable()).isFalse();
        assertThat(edited.recurring()).isFalse();
        assertThat(edited.active()).isFalse();
        assertThat(edited.createdAt()).isEqualTo(T0);
        assertThat(edited.updatedAt()).isEqualTo(T1);
    }

    @Test
    void aNewConceptStartsAtVersionZeroAndEveryEditAdvancesItByOne() {
        PaymentConcept created = concept();

        PaymentConcept first = created.edit(0, "Uno", PaymentCategory.OTHER, false, false, true, null, T1);
        PaymentConcept second = first.edit(1, "Dos", PaymentCategory.OTHER, false, false, true, null, T1);

        assertThat(created.version()).isZero();
        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
    }

    @Test
    void anEditThatNamesAnotherVersionThanTheCurrentOneIsStaleInBothDirections() {
        PaymentConcept current = concept().edit(0, "Uno", PaymentCategory.OTHER, false, false, true, null, T1);

        assertThatThrownBy(() -> current.edit(0, "X", PaymentCategory.OTHER, false, false, true, null, T1))
                .isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> current.edit(2, "X", PaymentCategory.OTHER, false, false, true, null, T1))
                .isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> current.edit(-1, "X", PaymentCategory.OTHER, false, false, true, null, T1))
                .isInstanceOf(StaleVersionException.class);
        assertThat(StaleVersionException.CODE).isEqualTo("stale-version");
    }

    @Test
    void aStaleEditIsRefusedBeforeItsContentIsJudged() {
        PaymentConcept current = concept();

        assertThatThrownBy(() -> current.edit(5, " ", PaymentCategory.OTHER, false, false, true, null, T1))
                .isInstanceOf(StaleVersionException.class);
    }

    @Test
    void anEditWithABlankNameIsRefused() {
        PaymentConcept before = concept();

        assertThatThrownBy(() -> before.edit(0, " ", PaymentCategory.TUITION, true, true, true, RATE, T1))
                .isInstanceOf(InvalidCatalogValueException.class);
    }

    @Test
    void aTaxableConceptNeedsATaxRateWhenCreatedOrEditedAndAConceptThatIsNotTaxableCannotHaveOne() {
        UUID id = UUID.randomUUID();
        PaymentConcept taxable = concept();

        assertThat(taxable.taxRateId()).isEqualTo(RATE);
        assertThatThrownBy(() -> PaymentConcept.create(id, "COD", "N", PaymentCategory.OTHER, true, false, null, T0))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> PaymentConcept.create(id, "COD", "N", PaymentCategory.OTHER, false, false, RATE, T0))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> taxable.edit(0, "N", PaymentCategory.OTHER, true, false, true, null, T1))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> taxable.edit(0, "N", PaymentCategory.OTHER, false, false, true, RATE, T1))
                .isInstanceOf(InvalidCatalogValueException.class);
        PaymentConcept notTaxable = taxable.edit(0, "N", PaymentCategory.OTHER, false, false, true, null, T1);
        assertThat(notTaxable.taxRateId()).isNull();
        assertThat(notTaxable.edit(1, "N", PaymentCategory.OTHER, true, false, true, RATE, T1).taxRateId())
                .isEqualTo(RATE);
    }

    @Test
    void aTaxableConceptReadFromTheStoreMayHaveNoTaxRateBecauseItWasCreatedBeforeTheyExisted() {
        PaymentConcept legacy = new PaymentConcept(UUID.randomUUID(), "COD", "Nombre", PaymentCategory.TUITION, true,
                true, true, null, 0, T0, T0);

        assertThat(legacy.taxRateId()).isNull();
        assertThatThrownBy(() -> new PaymentConcept(UUID.randomUUID(), "COD", "Nombre", PaymentCategory.TUITION,
                false, true, true, RATE, 0, T0, T0)).isInstanceOf(InvalidCatalogValueException.class);
    }

    @Test
    void aLegacyTaxableConceptWithoutARateStaysEditableButOneThatBecomesTaxableNeedsOne() {
        PaymentConcept legacy = new PaymentConcept(UUID.randomUUID(), "COD", "Nombre", PaymentCategory.TUITION, true,
                true, true, null, 0, T0, T0);

        PaymentConcept off = legacy.edit(0, "Otro", PaymentCategory.TUITION, true, true, false, null, T1);
        assertThat(off.taxRateId()).isNull();
        assertThat(off.active()).isFalse();
        assertThat(legacy.edit(0, "Otro", PaymentCategory.TUITION, true, true, true, RATE, T1).taxRateId())
                .isEqualTo(RATE);
        PaymentConcept plain = legacy.edit(0, "Otro", PaymentCategory.TUITION, false, true, true, null, T1);
        assertThatThrownBy(() -> plain.edit(1, "Otro", PaymentCategory.TUITION, true, true, true, null, T1))
                .isInstanceOf(InvalidCatalogValueException.class);
    }

    @Test
    void theErrorCodesAreTheOnesTheEdgePublishes() {
        assertThat(new InvalidCatalogValueException().code()).isEqualTo("validation-failed");
        assertThat(new DuplicateCodeException().code()).isEqualTo("duplicate-payment-concept-code");
        assertThat(new ResourceNotFoundException().code()).isEqualTo("resource-not-found");
    }
}
