package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link InstitutionId} is a thin, framework-free wrapper over {@link UUID} (design.md, decision
 * 2). A null value is a programming error (ADR-0019, point 6): it throws {@link
 * NullPointerException}, never a {@link DomainException} subclass, so this type adds no code to
 * {@code kernel}'s catalog and {@link KernelErrorCodesTest} is unaffected.
 */
class InstitutionIdTest {

    @Test
    void constructionFromAValidUuidExposesThatSameUuid() {
        UUID value = UUID.randomUUID();

        InstitutionId id = new InstitutionId(value);

        assertThat(id.value()).isEqualTo(value);
    }

    @Test
    void rejectsANullUuid() {
        assertThatThrownBy(() -> new InstitutionId(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void twoInstancesWithTheSameUuidAreEqualAndShareAHashCode() {
        UUID value = UUID.randomUUID();

        InstitutionId first = new InstitutionId(value);
        InstitutionId second = new InstitutionId(value);

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    void twoInstancesWithDifferentUuidsAreNotEqual() {
        InstitutionId first = new InstitutionId(UUID.randomUUID());
        InstitutionId second = new InstitutionId(UUID.randomUUID());

        assertThat(first).isNotEqualTo(second);
    }
}
