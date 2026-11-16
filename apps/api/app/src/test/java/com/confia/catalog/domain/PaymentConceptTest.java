package com.confia.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentConceptTest {

    private static final Instant T0 = Instant.parse("2026-01-10T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-02-10T12:00:00Z");

    private static PaymentConcept concept() {
        return PaymentConcept.create(UUID.randomUUID(), " COLEG ", " Colegiatura ", PaymentCategory.TUITION,
                true, true, T0);
    }

    @Test
    void aConceptStripsItsCodeAndNameAndIsBornActive() {
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

        assertThatThrownBy(() -> PaymentConcept.create(id, "  ", "Nombre", category, false, false, T0))
                .isInstanceOf(InvalidPaymentConceptException.class);
        assertThatThrownBy(() -> PaymentConcept.create(id, null, "Nombre", category, false, false, T0))
                .isInstanceOf(InvalidPaymentConceptException.class);
        assertThatThrownBy(() -> PaymentConcept.create(id, "C".repeat(31), "Nombre", category, false, false, T0))
                .isInstanceOf(InvalidPaymentConceptException.class);
        assertThatThrownBy(() -> PaymentConcept.create(id, "COD", "", category, false, false, T0))
                .isInstanceOf(InvalidPaymentConceptException.class);
        assertThatThrownBy(() -> PaymentConcept.create(id, "COD", null, category, false, false, T0))
                .isInstanceOf(InvalidPaymentConceptException.class);
        assertThatThrownBy(() -> PaymentConcept.create(id, "COD", "N".repeat(121), category, false, false, T0))
                .isInstanceOf(InvalidPaymentConceptException.class);
    }

    @Test
    void theLongestCodeAndNameAreAccepted() {
        PaymentConcept created = PaymentConcept.create(UUID.randomUUID(), "C".repeat(30), "N".repeat(120),
                PaymentCategory.FINE, false, false, T0);

        assertThat(created.code()).hasSize(30);
        assertThat(created.name()).hasSize(120);
    }

    @Test
    void aConceptWithoutACategoryIsRefused() {
        assertThatThrownBy(() -> PaymentConcept.create(UUID.randomUUID(), "COD", "Nombre", null, false, false, T0))
                .isInstanceOf(InvalidPaymentConceptException.class);
    }

    @Test
    void theCategoryIsAClosedSetOfSixAndAnythingElseIsRefused() {
        assertThat(PaymentCategory.values()).extracting(Enum::name).containsExactly("TUITION", "ENROLLMENT",
                "TRANSPORT", "MATERIAL", "FINE", "OTHER");
        for (PaymentCategory category : PaymentCategory.values()) {
            assertThat(PaymentCategory.parse(category.name())).isEqualTo(category);
        }
        assertThatThrownBy(() -> PaymentCategory.parse("tuition")).isInstanceOf(InvalidPaymentConceptException.class);
        assertThatThrownBy(() -> PaymentCategory.parse("OTHERS")).isInstanceOf(InvalidPaymentConceptException.class);
        assertThatThrownBy(() -> PaymentCategory.parse("")).isInstanceOf(InvalidPaymentConceptException.class);
        assertThatThrownBy(() -> PaymentCategory.parse(null)).isInstanceOf(InvalidPaymentConceptException.class);
    }

    @Test
    void anEditKeepsTheCodeTheIdentifierAndTheCreationInstantAndCanDeactivate() {
        PaymentConcept before = concept();

        PaymentConcept edited = before.edit("Mensualidad", PaymentCategory.OTHER, false, false, false, T1);

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
    void anEditWithABlankNameIsRefused() {
        PaymentConcept before = concept();

        assertThatThrownBy(() -> before.edit(" ", PaymentCategory.TUITION, true, true, true, T1))
                .isInstanceOf(InvalidPaymentConceptException.class);
    }

    @Test
    void theErrorCodesAreTheOnesTheEdgePublishes() {
        assertThat(new InvalidPaymentConceptException().code()).isEqualTo("validation-failed");
        assertThat(new DuplicateNameException().code()).isEqualTo("duplicate-name");
        assertThat(new ResourceNotFoundException().code()).isEqualTo("resource-not-found");
    }
}
