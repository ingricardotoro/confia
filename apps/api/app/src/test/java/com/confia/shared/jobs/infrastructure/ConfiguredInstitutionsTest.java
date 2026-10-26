package com.confia.shared.jobs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Scenarios BJ31 and BJ64 (background-jobs-with-db-scheduler, design.md decision 10, P5): the list
 * of institutions the worker acts on is explicit, non-empty, made of UUIDs and without repetition,
 * and a bad list stops the process with a message that names the property and the position of the
 * entry, never the list.
 */
class ConfiguredInstitutionsTest {

    private static final String PROPERTY = "confia.jobs.institutions";
    private static final String FIRST = "11111111-1111-4111-8111-111111111111";
    private static final String SECOND = "22222222-2222-4222-8222-222222222222";

    @Test
    void aValidListKeepsItsOrderAndTrimsBlanks() {
        ConfiguredInstitutions parsed = ConfiguredInstitutions.parse(" " + FIRST + " , " + SECOND);

        assertThat(parsed.institutions()).extracting(id -> id.value())
                .containsExactly(UUID.fromString(FIRST), UUID.fromString(SECOND));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", ",", " , "})
    void anAbsentOrEmptyListIsRefusedNamingTheProperty(String raw) {
        assertThatThrownBy(() -> ConfiguredInstitutions.parse(raw))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(PROPERTY)
                .hasMessageContaining("at least one");
    }

    @Test
    void anInvalidEntryIsRefusedNamingItsPositionWithoutRepeatingTheList() {
        String raw = FIRST + ",no-es-uuid";

        assertThatThrownBy(() -> ConfiguredInstitutions.parse(raw))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(PROPERTY)
                .hasMessageContaining("entry 2").hasMessageContaining("UUID")
                .hasMessageNotContaining(FIRST).hasMessageNotContaining("no-es-uuid");
    }

    @Test
    void aBlankEntryBetweenTwoValidOnesIsInvalidAtItsPosition() {
        assertThatThrownBy(() -> ConfiguredInstitutions.parse(FIRST + ",," + SECOND))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("entry 2")
                .hasMessageNotContaining(FIRST);
    }

    @Test
    void aRepeatedEntryIsRefusedNamingBothPositionsWithoutRepeatingTheList() {
        assertThatThrownBy(() -> ConfiguredInstitutions.parse(FIRST + "," + SECOND + "," + FIRST))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(PROPERTY)
                .hasMessageContaining("entry 3").hasMessageContaining("entry 1")
                .hasMessageNotContaining(FIRST).hasMessageNotContaining(SECOND);
    }

    @Test
    void theSameUuidInDifferentLetterCaseIsStillARepetition() {
        assertThatThrownBy(() -> ConfiguredInstitutions.parse(
                "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA,aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("entry 2");
    }

    @Test
    void theListIsImmutable() {
        ConfiguredInstitutions parsed = ConfiguredInstitutions.parse(FIRST);

        assertThatThrownBy(() -> parsed.institutions().add(parsed.institutions().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
