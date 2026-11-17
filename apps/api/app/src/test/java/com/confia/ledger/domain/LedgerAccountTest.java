package com.confia.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rules of an account of the chart (docs/cortes/ledger-chart-of-accounts.md): the shape of the code, the name, the type, the
 * student flag that only an asset or a liability may carry, and the edit of the name and the active flag with the version the
 * editor read. Codes in the tests are fictional.
 */
class LedgerAccountTest {

    private static final Instant NOW = Instant.parse("2026-11-17T15:00:00Z");
    private static final Instant LATER = Instant.parse("2026-11-17T16:00:00Z");

    private static LedgerAccount account(String code, String name, AccountType type, boolean student) {
        return LedgerAccount.create(UUID.randomUUID(), code, name, type, student, NOW);
    }

    @Test
    void anAccountIsBornActiveAtVersionZeroWithItsCodeAndNameTrimmed() {
        LedgerAccount account = account("  X-100 ", "  Cuenta de prueba ", AccountType.ASSET, true);

        assertThat(account.code()).isEqualTo("X-100");
        assertThat(account.name()).isEqualTo("Cuenta de prueba");
        assertThat(account.active()).isTrue();
        assertThat(account.version()).isZero();
        assertThat(account.createdAt()).isEqualTo(NOW);
        assertThat(account.updatedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "-1", ".A", "A B", "A/B", "ÑA", "A\nB","012345678901234567890123456789X"})
    void aCodeOutsideTheShapeOfTheDatabaseIsRefused(String code) {
        assertThatThrownBy(() -> account(code, "Cuenta", AccountType.ASSET, false))
                .isInstanceOf(InvalidLedgerAccountException.class);
    }

    @Test
    void aMissingCodeIsRefused() {
        assertThatThrownBy(() -> account(null, "Cuenta", AccountType.ASSET, false))
                .isInstanceOf(InvalidLedgerAccountException.class);
    }

    @Test
    void aCodeOfThirtyCharactersIsTheLongestAccepted() {
        assertThat(account("A".repeat(30), "Cuenta", AccountType.EXPENSE, false).code()).hasSize(30);
        assertThatThrownBy(() -> account("A".repeat(31), "Cuenta", AccountType.EXPENSE, false))
                .isInstanceOf(InvalidLedgerAccountException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void aBlankNameIsRefused(String name) {
        assertThatThrownBy(() -> account("X-1", name, AccountType.ASSET, false))
                .isInstanceOf(InvalidLedgerAccountException.class);
    }

    @Test
    void aNameWithAControlCharacterOrOver120CharactersIsRefusedAndOneOf120IsKept() {
        assertThatThrownBy(() -> account("X-1", "Cuenta\u0007", AccountType.ASSET, false))
                .isInstanceOf(InvalidLedgerAccountException.class);
        assertThatThrownBy(() -> account("X-1", "a".repeat(121), AccountType.ASSET, false))
                .isInstanceOf(InvalidLedgerAccountException.class);
        assertThatThrownBy(() -> account("X-1", null, AccountType.ASSET, false))
                .isInstanceOf(InvalidLedgerAccountException.class);
        assertThat(account("X-1", "a".repeat(120), AccountType.ASSET, false).name()).hasSize(120);
    }

    @Test
    void onlyAnAssetOrALiabilityMayHoldStudentBalances() {
        assertThat(account("X-1", "Por cobrar", AccountType.ASSET, true).holdsStudentBalances()).isTrue();
        assertThat(account("X-2", "Saldo a favor", AccountType.LIABILITY, true).holdsStudentBalances()).isTrue();
        for (AccountType type : new AccountType[] {AccountType.EQUITY, AccountType.INCOME, AccountType.EXPENSE}) {
            assertThatThrownBy(() -> account("X-3", "Cuenta", type, true))
                    .as(type.name()).isInstanceOf(InvalidLedgerAccountException.class);
        }
    }

    @Test
    void aTypeOutsideTheSetOrWithOtherCapitalsIsRefused() {
        assertThat(AccountType.parse("INCOME")).isEqualTo(AccountType.INCOME);
        for (String value : new String[] {null, "", "income", "Income", "REVENUE", " INCOME"}) {
            assertThatThrownBy(() -> AccountType.parse(value)).as(String.valueOf(value))
                    .isInstanceOf(InvalidLedgerAccountException.class);
        }
    }

    @Test
    void anEditChangesTheNameAndTheActiveFlagAndMovesTheVersionByOne() {
        LedgerAccount before = account("X-1", "Cuenta", AccountType.INCOME, false);

        LedgerAccount after = before.edit(0, "Otro nombre", false, LATER);

        assertThat(after.name()).isEqualTo("Otro nombre");
        assertThat(after.active()).isFalse();
        assertThat(after.version()).isEqualTo(1);
        assertThat(after.updatedAt()).isEqualTo(LATER);
        assertThat(after.createdAt()).isEqualTo(NOW);
        assertThat(after.code()).isEqualTo(before.code());
        assertThat(after.type()).isEqualTo(before.type());
        assertThat(after.holdsStudentBalances()).isEqualTo(before.holdsStudentBalances());
        assertThat(before.version()).as("the account is immutable").isZero();
    }

    @Test
    void anEditWithAnotherVersionIsStaleEvenIfTheValuesAreTheSame() {
        LedgerAccount account = account("X-1", "Cuenta", AccountType.INCOME, false);

        assertThatThrownBy(() -> account.edit(1, "Cuenta", true, LATER)).isInstanceOf(StaleAccountVersionException.class);
        assertThatThrownBy(() -> account.edit(-1, "Cuenta", true, LATER)).isInstanceOf(StaleAccountVersionException.class);
        assertThat(account.edit(0, "Cuenta", true, LATER).version()).isEqualTo(1);
    }

    @Test
    void anEditWithABadNameIsRefusedAndDeactivatesIsTrueOnlyForTheActiveOneTurnedOff() {
        LedgerAccount account = account("X-1", "Cuenta", AccountType.INCOME, false);

        assertThatThrownBy(() -> account.edit(0, " ", true, LATER)).isInstanceOf(InvalidLedgerAccountException.class);
        assertThat(account.deactivates(false)).isTrue();
        assertThat(account.deactivates(true)).isFalse();
        assertThat(account.edit(0, "Cuenta", false, LATER).deactivates(false)).isFalse();
    }

    @Test
    void domainErrorsCarryTheCodesPublishedToClients() {
        assertThat(new DuplicateAccountCodeException().code()).isEqualTo("duplicate-account-code");
        assertThat(new AccountInUseException().code()).isEqualTo("account-in-use");
        assertThat(new StaleAccountVersionException().code()).isEqualTo("stale-version");
        assertThat(new InvalidLedgerAccountException().code()).isEqualTo("validation-failed");
    }
}
