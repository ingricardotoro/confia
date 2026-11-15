package com.confia.organization.application.directory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SectionNamesTest {

    @Test
    void twoSpellingsOfTheSameNamesAreEqual() {
        assertThat(new SectionNames(" 2026 ", "REGULAR", "Septimo  Grado", "a"))
                .isEqualTo(new SectionNames("2026", "regular", "septimo grado", " A "));
    }

    @Test
    void tabsAndMixedWhitespaceCollapseToOneSpace() {
        assertThat(new SectionNames("2026", "Regular", "Septimo\t \tGrado", "A"))
                .isEqualTo(new SectionNames("2026", "Regular", "Septimo Grado", "A"));
    }

    @Test
    void differentNamesStayDifferent() {
        assertThat(new SectionNames("2026", "Regular", "Septimo", "A"))
                .isNotEqualTo(new SectionNames("2026", "Regular", "Septimo", "B"));
    }
}
