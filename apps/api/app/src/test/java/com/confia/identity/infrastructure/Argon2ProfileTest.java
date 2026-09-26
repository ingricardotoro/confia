package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link Argon2Profile}'s constant values against {@code docs/03-seguridad.md} §4.1's own table
 * (design.md, decision 6): {@code memoryCost=19456}, {@code timeCost=3}, {@code parallelism=1},
 * a 32-byte output, a 16-byte salt and a 32-byte pepper. Declared as the floor this change ships
 * with, never as a value calibrated against real production hardware
 * (specs/identity/spec.md, "Ausencia de calibración de Argon2id en servidor real") — calibration,
 * with its own ADR naming hardware and a date, is change 11's job (design.md §11, paso 11).
 */
class Argon2ProfileTest {

    @Test
    void theFloorMatchesTheSeguridadDocTableExactly() {
        Argon2Profile floor = Argon2Profile.floor();

        assertThat(floor.memoryCostKib()).isEqualTo(19_456);
        assertThat(floor.timeCost()).isEqualTo(3);
        assertThat(floor.parallelism()).isEqualTo(1);
        assertThat(floor.hashLength()).isEqualTo(32);
        assertThat(floor.saltLength()).isEqualTo(16);
    }

    @Test
    void thePepperLengthConstantIs32Bytes() {
        assertThat(Argon2Profile.PEPPER_LENGTH_BYTES).isEqualTo(32);
    }

    @Test
    void rejectsANonPositiveMemoryCost() {
        assertThatThrownBy(() -> new Argon2Profile(0, 3, 1, 32, 16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANonPositiveTimeCost() {
        assertThatThrownBy(() -> new Argon2Profile(19_456, 0, 1, 32, 16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANonPositiveParallelism() {
        assertThatThrownBy(() -> new Argon2Profile(19_456, 3, 0, 32, 16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANonPositiveHashLength() {
        assertThatThrownBy(() -> new Argon2Profile(19_456, 3, 1, 0, 16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANonPositiveSaltLength() {
        assertThatThrownBy(() -> new Argon2Profile(19_456, 3, 1, 32, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
