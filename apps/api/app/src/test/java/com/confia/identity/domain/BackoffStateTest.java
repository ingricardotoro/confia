package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * {@link BackoffState} construction guards (ADR-0019, point 6): each field is a programming-error
 * check, never a {@code DomainException} subclass — {@code consecutiveFailures} is the one
 * domain-shaped exception because a negative count can only come from a caller bug, not from any
 * external input this record ever parses directly.
 */
class BackoffStateTest {

    private static final Instant NOW = Instant.parse("2026-03-10T08:00:00Z");

    @Test
    void exposesItsTwoComponents() {
        BackoffState state = new BackoffState(2, NOW);

        assertThat(state.consecutiveFailures()).isEqualTo(2);
        assertThat(state.lastAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsANegativeConsecutiveFailuresCount() {
        assertThatThrownBy(() -> new BackoffState(-1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsZeroConsecutiveFailures() {
        BackoffState state = new BackoffState(0, NOW);

        assertThat(state.consecutiveFailures()).isZero();
    }

    @Test
    void rejectsANullLastAttemptAt() {
        assertThatThrownBy(() -> new BackoffState(0, null))
                .isInstanceOf(NullPointerException.class);
    }
}
