package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The shape rules of {@link RefreshTokenFamily} and {@link RefreshTokenRow}: the same windows the
 * {@code CHECK}s of {@code V8} enforce, refused before any statement is sent, and a row that never
 * prints its hash.
 */
class RefreshTokenRecordsTest {

    private static final Instant CREATED = Instant.parse("2026-10-12T08:00:00Z");
    private static final Instant ABSOLUTE = Instant.parse("2026-10-12T20:00:00Z");
    private static final StaffAccountId ACCOUNT = new StaffAccountId(UUID.randomUUID());
    private static final UserAgentFingerprint FINGERPRINT = UserAgentFingerprint.of("agente");
    private static final RefreshTokenHash HASH = new RefreshTokenHash("a".repeat(64));

    @Test
    void aFamilyAcceptsAWindowThatStartsAtCreationAndEndsLater() {
        RefreshTokenFamily family = new RefreshTokenFamily(UUID.randomUUID(), ACCOUNT, true,
                FINGERPRINT, "203.0.113.9", CREATED, CREATED, ABSOLUTE);

        assertThat(family.ipFirstSeen()).isEqualTo("203.0.113.9");
        assertThat(new RefreshTokenFamily(UUID.randomUUID(), ACCOUNT, false, FINGERPRINT, null,
                CREATED, CREATED, ABSOLUTE).ipFirstSeen()).isNull();
    }

    @Test
    void aFamilyRefusesAnEndThatIsNotAfterItsCreationAndAUseBeforeIt() {
        assertThatThrownBy(() -> new RefreshTokenFamily(UUID.randomUUID(), ACCOUNT, false,
                FINGERPRINT, null, CREATED, CREATED, CREATED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshTokenFamily(UUID.randomUUID(), ACCOUNT, false,
                FINGERPRINT, null, CREATED, CREATED.minusSeconds(1), ABSOLUTE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFamilyRefusesMissingParts() {
        assertThatThrownBy(() -> new RefreshTokenFamily(null, ACCOUNT, false, FINGERPRINT, null,
                CREATED, CREATED, ABSOLUTE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshTokenFamily(UUID.randomUUID(), null, false, FINGERPRINT,
                null, CREATED, CREATED, ABSOLUTE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshTokenFamily(UUID.randomUUID(), ACCOUNT, false, null,
                null, CREATED, CREATED, ABSOLUTE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshTokenFamily(UUID.randomUUID(), ACCOUNT, false,
                FINGERPRINT, null, null, CREATED, ABSOLUTE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshTokenFamily(UUID.randomUUID(), ACCOUNT, false,
                FINGERPRINT, null, CREATED, null, ABSOLUTE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshTokenFamily(UUID.randomUUID(), ACCOUNT, false,
                FINGERPRINT, null, CREATED, CREATED, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aRowNeedsAnExpiryAfterItsIssueAndNeverPrintsItsHash() {
        UUID id = UUID.randomUUID();
        UUID family = UUID.randomUUID();

        RefreshTokenRow row = new RefreshTokenRow(id, family, HASH, CREATED, ABSOLUTE);

        assertThat(row.toString()).doesNotContain("a".repeat(16));
        assertThatThrownBy(() -> new RefreshTokenRow(id, family, HASH, CREATED, CREATED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshTokenRow(null, family, HASH, CREATED, ABSOLUTE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshTokenRow(id, null, HASH, CREATED, ABSOLUTE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshTokenRow(id, family, null, CREATED, ABSOLUTE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshTokenRow(id, family, HASH, null, ABSOLUTE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshTokenRow(id, family, HASH, CREATED, null))
                .isInstanceOf(NullPointerException.class);
    }
}
