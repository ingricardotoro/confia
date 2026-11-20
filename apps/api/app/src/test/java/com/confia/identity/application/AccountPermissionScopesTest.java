package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StaffRole;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Permission;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The scope of a permission by role: only the cashier is limited, and anything unknown is limited (fails closed). */
class AccountPermissionScopesTest {

    private static final Instant NOW = Instant.parse("2027-02-10T18:00:00Z");

    private static AuthenticatedActor actor() {
        return new AuthenticatedActor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    }

    private static AccountPermissionScopes withCode(String code) {
        return new AccountPermissionScopes((institution, account) -> Optional.ofNullable(code));
    }

    @Test
    void aCashierIsRestrictedToItsOwnCashboxReads() {
        assertThat(withCode("cashier").restrictedToOwn(actor(), Permission.CASHBOX_READ)).isTrue();
    }

    @Test
    void aCashierIsNotRestrictedOnAPermissionThatIsNotOwnScoped() {
        assertThat(withCode("cashier").restrictedToOwn(actor(), Permission.PAYMENTS_READ)).isFalse();
    }

    @Test
    void everyOtherRoleReadsEverything() {
        for (StaffRole role : StaffRole.values()) {
            if (role != StaffRole.CASHIER) {
                assertThat(withCode(role.code()).restrictedToOwn(actor(), Permission.CASHBOX_READ)).as(role.code())
                        .isFalse();
            }
        }
    }

    @Test
    void noAssignmentOrAnUnknownCodeIsRestricted() {
        assertThat(withCode(null).restrictedToOwn(actor(), Permission.CASHBOX_READ)).isTrue();
        assertThat(withCode("treasurer").restrictedToOwn(actor(), Permission.CASHBOX_READ)).isTrue();
        assertThat(withCode("").restrictedToOwn(actor(), Permission.CASHBOX_READ)).isTrue();
    }

    @Test
    void itAsksForTheInstitutionAndTheAccountOfTheActor() {
        AuthenticatedActor actor = actor();
        InstitutionId[] asked = new InstitutionId[1];
        StaffAccountId[] account = new StaffAccountId[1];
        new AccountPermissionScopes((institution, id) -> {
            asked[0] = institution;
            account[0] = id;
            return Optional.of("accounting");
        }).restrictedToOwn(actor, Permission.CASHBOX_READ);

        assertThat(asked[0]).isEqualTo(new InstitutionId(actor.institutionId()));
        assertThat(account[0]).isEqualTo(new StaffAccountId(actor.accountId()));
    }

    @Test
    void aFailureOfTheStorePropagatesAndNeverReadsAsUnrestricted() {
        AccountPermissionScopes scopes = new AccountPermissionScopes((institution, account) -> {
            throw new IllegalStateException("store down");
        });

        assertThatThrownBy(() -> scopes.restrictedToOwn(actor(), Permission.CASHBOX_READ))
                .isInstanceOf(IllegalStateException.class);
    }
}
