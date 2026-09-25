package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link StaffAccountId} is a thin, framework-free wrapper over {@link UUID}, following
 * {@code com.confia.kernel.InstitutionId}'s own precedent test. A null value is a programming
 * error (ADR-0019, point 6): it throws {@link NullPointerException}, never a
 * {@code DomainException} subclass.
 */
class StaffAccountIdTest {

    @Test
    void constructionFromAValidUuidExposesThatSameUuid() {
        UUID value = UUID.randomUUID();

        StaffAccountId id = new StaffAccountId(value);

        assertThat(id.value()).isEqualTo(value);
    }

    @Test
    void rejectsANullUuid() {
        assertThatThrownBy(() -> new StaffAccountId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void twoInstancesWithTheSameUuidAreEqualAndShareAHashCode() {
        UUID value = UUID.randomUUID();

        StaffAccountId first = new StaffAccountId(value);
        StaffAccountId second = new StaffAccountId(value);

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    void twoInstancesWithDifferentUuidsAreNotEqual() {
        StaffAccountId first = new StaffAccountId(UUID.randomUUID());
        StaffAccountId second = new StaffAccountId(UUID.randomUUID());

        assertThat(first).isNotEqualTo(second);
    }
}
