package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The production policy of the second factor (rbac-permission-matrix-and-audit-integration
 * design.md, D14; owner decisions O4 and Q1): every staff account owes one, whatever it is.
 */
class MfaPolicyTest {

    static Stream<StaffAccountId> accounts() {
        return Stream.concat(
                Stream.of(new StaffAccountId(new UUID(0L, 0L)),
                        new StaffAccountId(new UUID(-1L, -1L)),
                        new StaffAccountId(new UUID(Long.MIN_VALUE, Long.MAX_VALUE))),
                Stream.generate(() -> new StaffAccountId(UUID.randomUUID())).limit(50));
    }

    @ParameterizedTest
    @MethodSource("accounts")
    void theStaffPolicyRequiresASecondFactorOfEveryAccount(StaffAccountId account) {
        assertThat(MfaPolicy.STAFF.requiresSecondFactor(account)).isTrue();
    }

    @Test
    void theStaffPolicyHasNoStateAndAnswersTheSameToTheSameAccount() {
        StaffAccountId account = new StaffAccountId(UUID.randomUUID());

        assertThat(MfaPolicy.STAFF.requiresSecondFactor(account))
                .isEqualTo(MfaPolicy.STAFF.requiresSecondFactor(account)).isTrue();
        assertThat(MfaPolicy.STAFF).isSameAs(MfaPolicy.STAFF);
    }

    @Test
    void aPolicyDecidesFromTheAccountItIsGiven() {
        StaffAccountId exempt = new StaffAccountId(UUID.randomUUID());
        MfaPolicy exempting = account -> !account.equals(exempt);

        assertThat(exempting.requiresSecondFactor(exempt)).isFalse();
        assertThat(exempting.requiresSecondFactor(new StaffAccountId(UUID.randomUUID()))).isTrue();
    }
}
