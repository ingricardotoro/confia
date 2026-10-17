package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The shape rules of {@link RefreshTokenFamilyState}, {@link RefreshTokenState} and {@link
 * RefreshRejection}: the same coherence the {@code CHECK}s of {@code V8} enforce on what is stored,
 * refused when a state is read back incoherently, and a closed vocabulary for the audit entry.
 */
class RefreshTokenStateRecordsTest {

    private static final Instant ISSUED = Instant.parse("2026-10-12T08:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-12T16:00:00Z");
    private static final StaffAccountId ACCOUNT = new StaffAccountId(UUID.randomUUID());
    private static final UserAgentFingerprint FINGERPRINT = UserAgentFingerprint.of("agente");

    @Test
    void aFamilyStateKnowsWhetherItIsRevoked() {
        assertThat(new RefreshTokenFamilyState(UUID.randomUUID(), ACCOUNT, false, ISSUED, EXPIRES,
                null).isRevoked()).isFalse();
        assertThat(new RefreshTokenFamilyState(UUID.randomUUID(), ACCOUNT, true, ISSUED, EXPIRES,
                ISSUED.plusSeconds(1)).isRevoked()).isTrue();
    }

    @Test
    void aFamilyStateRefusesMissingParts() {
        assertThatThrownBy(() -> new RefreshTokenFamilyState(null, ACCOUNT, false, ISSUED, EXPIRES,
                null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshTokenFamilyState(UUID.randomUUID(), null, false, ISSUED,
                EXPIRES, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshTokenFamilyState(UUID.randomUUID(), ACCOUNT, false,
                null, EXPIRES, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshTokenFamilyState(UUID.randomUUID(), ACCOUNT, false,
                ISSUED, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aTokenStateKnowsWhetherItIsConsumed() {
        assertThat(new RefreshTokenState(UUID.randomUUID(), UUID.randomUUID(), ISSUED, EXPIRES,
                null, null, false, false).isConsumed()).isFalse();
        assertThat(new RefreshTokenState(UUID.randomUUID(), UUID.randomUUID(), ISSUED, EXPIRES,
                ISSUED.plusSeconds(5), FINGERPRINT, true, false).isConsumed()).isTrue();
    }

    @Test
    void consumptionIsAllOrNothingAndNothingElseDependsOnItAlone() {
        assertThatThrownBy(() -> new RefreshTokenState(UUID.randomUUID(), UUID.randomUUID(), ISSUED,
                EXPIRES, ISSUED.plusSeconds(5), null, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshTokenState(UUID.randomUUID(), UUID.randomUUID(), ISSUED,
                EXPIRES, null, FINGERPRINT, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshTokenState(UUID.randomUUID(), UUID.randomUUID(), ISSUED,
                EXPIRES, null, null, true, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshTokenState(UUID.randomUUID(), UUID.randomUUID(), ISSUED,
                EXPIRES, null, null, false, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTokenStateRefusesMissingParts() {
        assertThatThrownBy(() -> new RefreshTokenState(null, UUID.randomUUID(), ISSUED, EXPIRES,
                null, null, false, false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshTokenState(UUID.randomUUID(), null, ISSUED, EXPIRES,
                null, null, false, false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshTokenState(UUID.randomUUID(), UUID.randomUUID(), null,
                EXPIRES, null, null, false, false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshTokenState(UUID.randomUUID(), UUID.randomUUID(), ISSUED,
                null, null, null, false, false)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aRejectionSpeaksTheClosedVocabularyOfTheAuditEntry() {
        assertThat(RefreshRejection.values()).extracting(RefreshRejection::auditCode)
                .containsExactly("unknown_token", "family_revoked", "family_expired",
                        "token_expired", "reuse_detected");
    }

    @Test
    void theTokenStatePrintsNoFingerprint() {
        RefreshTokenState state = new RefreshTokenState(UUID.randomUUID(), UUID.randomUUID(),
                ISSUED, EXPIRES, ISSUED.plusSeconds(5), FINGERPRINT, true, false);

        assertThat(state.toString()).doesNotContain(FINGERPRINT.value());
    }
}
