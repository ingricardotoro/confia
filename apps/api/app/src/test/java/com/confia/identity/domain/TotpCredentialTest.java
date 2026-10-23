package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A credential is verified only once it accepted a TOTP code (session-endpoints-cookie-and-csrf
 * design.md, decision 13; specs/identity, [I167] and [I168]): its last accepted counter is at least
 * zero. The sentinel {@code -1} of a credential that was enrolled and never verified is the only
 * unverified value.
 */
class TotpCredentialTest {

    private static final StaffAccountId ACCOUNT = new StaffAccountId(UUID.randomUUID());
    private static final String SECRET = "v1:dek:aXY=:Y3Q=:dGFn";

    @ParameterizedTest
    @CsvSource({"-1, false", "0, true", "1, true", "58204, true", "9223372036854775807, true"})
    void aCredentialIsVerifiedFromTheFirstAcceptedCounterOnward(long counter, boolean verified) {
        assertThat(new TotpCredential(ACCOUNT, SECRET, counter).isVerified()).isEqualTo(verified);
    }

    @Test
    void theAccountAndTheStoredSecretAreRequired() {
        assertThatThrownBy(() -> new TotpCredential(null, SECRET, -1L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TotpCredential(ACCOUNT, null, -1L))
                .isInstanceOf(NullPointerException.class);
    }
}
