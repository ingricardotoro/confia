package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * Properties of {@link JobInstanceId#derive} (design.md decision 6; scenarios BJ15 and BJ56): 32
 * lowercase hexadecimal characters, deterministic, and sensitive to the type name and to each part
 * in its position. A fixed vector pins the derivation, so changing the {@code "v1|"} prefix cannot
 * pass unnoticed.
 */
class JobInstanceIdProperties {

    private static final String NAME = "identity.password-reset-issuance";
    private static final UUID ONE = new UUID(0, 1);
    private static final UUID TWO = new UUID(0, 2);
    private static final UUID THREE = new UUID(0, 3);

    @Test
    void theDerivationMatchesTheFixedVector() {
        // SHA-256("v1|identity.password-reset-issuance|<one>|<two>|<three>"), first 32 hex characters,
        // computed outside the code under test.
        assertThat(JobInstanceId.derive(NAME, ONE, TWO, THREE).value())
                .isEqualTo("a407230062939af315a0fd96e501d554");
    }

    @Test
    void theVersionTwoPrefixWouldProduceADifferentValue() {
        // SHA-256 of the same text with "v2|" instead of "v1|": the derivation is not that one.
        assertThat(JobInstanceId.derive(NAME, ONE, TWO, THREE).value())
                .isNotEqualTo("be16a24912e01ace372c882544ea8729");
    }

    @Property(tries = 300)
    void theValueIsThirtyTwoLowercaseHexadecimalCharacters(@ForAll("names") String name,
            @ForAll("parts") UUID[] parts) {
        assertThat(JobInstanceId.derive(name, parts).value()).matches("[0-9a-f]{32}");
    }

    @Property(tries = 300)
    void theDerivationIsDeterministic(@ForAll("names") String name, @ForAll("parts") UUID[] parts) {
        assertThat(JobInstanceId.derive(name, parts)).isEqualTo(JobInstanceId.derive(name, parts));
    }

    @Property(tries = 300)
    void changingTheTypeNameChangesTheValue(@ForAll("names") String name, @ForAll("names") String other,
            @ForAll("parts") UUID[] parts) {
        if (name.equals(other)) {
            return;
        }
        assertThat(JobInstanceId.derive(name, parts)).isNotEqualTo(JobInstanceId.derive(other, parts));
    }

    @Property(tries = 300)
    void changingAnyOnePartChangesTheValue(@ForAll("names") String name, @ForAll("parts") UUID[] parts,
            @ForAll("uuids") UUID replacement) {
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(replacement)) {
                continue;
            }
            UUID[] changed = parts.clone();
            changed[i] = replacement;
            assertThat(JobInstanceId.derive(name, changed)).isNotEqualTo(JobInstanceId.derive(name, parts));
        }
    }

    @Property(tries = 300)
    void theOrderOfThePartsMatters(@ForAll("names") String name, @ForAll("uuids") UUID first,
            @ForAll("uuids") UUID second) {
        if (first.equals(second)) {
            return;
        }
        assertThat(JobInstanceId.derive(name, first, second))
                .isNotEqualTo(JobInstanceId.derive(name, second, first));
    }

    @Property(tries = 100)
    void theValueCarriesNoSeparatorOfItsInputs(@ForAll("names") String name, @ForAll("parts") UUID[] parts) {
        assertThat(JobInstanceId.derive(name, parts).value()).doesNotContain("-").doesNotContain(".");
    }

    @Test
    void rejectsAMalformedValue() {
        assertThatThrownBy(() -> new JobInstanceId("A407230062939AF315A0FD96E501D554"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobInstanceId("a407230062939af315a0fd96e501d55"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobInstanceId("a407230062939af315a0fd96e501d5540"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobInstanceId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsAnInvalidTypeNameAndMissingParts() {
        assertThatThrownBy(() -> JobInstanceId.derive("Not A Name", ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobInstanceId.derive(NAME)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobInstanceId.derive(NAME, ONE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Provide
    Arbitrary<String> names() {
        return Arbitraries.of(NAME, "shared.audit-chain-verification-daily", "identity.purge-refresh-tokens",
                "a.b", "identity.purge-login-backoff");
    }

    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.randomValue(random -> new UUID(random.nextLong(), random.nextLong()));
    }

    @Provide
    Arbitrary<UUID[]> parts() {
        return uuids().array(UUID[].class).ofMinSize(1).ofMaxSize(4);
    }
}
