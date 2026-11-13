package com.confia.imports.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.imports.domain.Reconciliation.KindCount;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconciliationTest {

    private static final KindCount NONE = new KindCount(0, 0);

    private static Reconciliation record(String reference, int official, int active) {
        return new Reconciliation(UUID.randomUUID(), UUID.randomUUID(), reference, official, active, NONE, NONE, NONE,
                NONE, UUID.randomUUID(), Instant.parse("2026-11-13T15:00:00Z"));
    }

    @Test
    void theDifferenceIsTheActiveEnrollmentsMinusTheOfficialTotal() {
        assertThat(record("OF-1", 100, 103).difference()).isEqualTo(3);
        assertThat(record("OF-1", 100, 97).difference()).isEqualTo(-3);
        assertThat(record("OF-1", 100, 100).difference()).isZero();
        assertThat(record("OF-1", 0, 0).difference()).isZero();
    }

    @Test
    void aReferenceOfOneAndOfTheMaximumLengthIsAccepted() {
        assertThat(record("A", 0, 0).officialReference()).isEqualTo("A");
        String longest = "x".repeat(Reconciliation.MAX_REFERENCE_LENGTH);
        assertThat(record(longest, 0, 0).officialReference()).isEqualTo(longest);
    }

    @Test
    void aReferenceThatIsBlankPaddedTooLongOrHasControlCharactersIsRefused() {
        assertThat(Reconciliation.isValidReference("")).isFalse();
        assertThat(Reconciliation.isValidReference(" ")).isFalse();
        assertThat(Reconciliation.isValidReference(" OF-1")).isFalse();
        assertThat(Reconciliation.isValidReference("OF-1 ")).isFalse();
        assertThat(Reconciliation.isValidReference("x".repeat(Reconciliation.MAX_REFERENCE_LENGTH + 1))).isFalse();
        assertThat(Reconciliation.isValidReference("OF\n1")).isFalse();
        assertThat(Reconciliation.isValidReference("OF\u00851")).isFalse();
        assertThat(Reconciliation.isValidReference(null)).isFalse();
        assertThatThrownBy(() -> record(" OF-1", 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> record("", 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNegativeCountIsRefused() {
        assertThatThrownBy(() -> record("OF-1", -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> record("OF-1", 0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KindCount(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KindCount(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyPartIsRequired() {
        assertThatThrownBy(() -> new Reconciliation(null, UUID.randomUUID(), "OF-1", 0, 0, NONE, NONE, NONE, NONE,
                UUID.randomUUID(), Instant.now())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Reconciliation(UUID.randomUUID(), UUID.randomUUID(), "OF-1", 0, 0, null, NONE,
                NONE, NONE, UUID.randomUUID(), Instant.now())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Reconciliation(UUID.randomUUID(), UUID.randomUUID(), "OF-1", 0, 0, NONE, NONE,
                NONE, NONE, null, Instant.now())).isInstanceOf(NullPointerException.class);
    }
}
