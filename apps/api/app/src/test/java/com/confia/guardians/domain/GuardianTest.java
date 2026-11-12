package com.confia.guardians.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The rules of the fields of a guardian (docs/cortes/guardians-registry.md) and the redaction of its protected data. */
class GuardianTest {

    private static final Instant NOW = Instant.parse("2026-03-10T15:00:00Z");
    private static final Instant LATER = Instant.parse("2026-03-11T15:00:00Z");

    private static Guardian guardian(String first, String last, NationalId id, Rtn rtn, String email, String phone,
            String address, String channel) {
        return Guardian.create(UUID.randomUUID(), "P-000001", first, last, id, rtn, email, phone, address, channel,
                NOW);
    }

    private static Guardian simple() {
        return guardian("Ana", "Lopez", null, null, null, null, null, null);
    }

    @Test
    void aNewGuardianIsActiveWithNoChannelByDefaultAndItsTextsAreStripped() {
        Guardian guardian = guardian("  Ana ", " Lopez ", null, null, " ana@example.org ", " +504 9999-8888 ",
                " Colonia Kennedy ", null);

        assertThat(guardian.status()).isEqualTo(GuardianStatus.ACTIVE);
        assertThat(guardian.preferredChannel()).isEqualTo(PreferredChannel.NONE);
        assertThat(guardian.firstName()).isEqualTo("Ana");
        assertThat(guardian.lastName()).isEqualTo("Lopez");
        assertThat(guardian.email()).isEqualTo("ana@example.org");
        assertThat(guardian.phone()).isEqualTo("+504 9999-8888");
        assertThat(guardian.address()).isEqualTo("Colonia Kennedy");
        assertThat(guardian.createdAt()).isEqualTo(NOW).isEqualTo(guardian.updatedAt());
        assertThat(guardian.active()).isTrue();
    }

    @Test
    void theNamesAreRequiredAndAtMostOneHundredCharacters() {
        assertThatThrownBy(() -> guardian(null, "Lopez", null, null, null, null, null, null))
                .isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> guardian("  ", "Lopez", null, null, null, null, null, null))
                .isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> guardian("Ana", "", null, null, null, null, null, null))
                .isInstanceOf(InvalidGuardianException.class);
        assertThat(guardian("A".repeat(100), "B".repeat(100), null, null, null, null, null, null).firstName())
                .hasSize(100);
        assertThatThrownBy(() -> guardian("A".repeat(101), "Lopez", null, null, null, null, null, null))
                .isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> guardian("Ana", "B".repeat(101), null, null, null, null, null, null))
                .isInstanceOf(InvalidGuardianException.class);
    }

    @Test
    void theEmailMustLookLikeOneAndABlankOneIsAbsent() {
        assertThat(guardian("Ana", "Lopez", null, null, "  ", null, null, null).email()).isNull();
        assertThat(guardian("Ana", "Lopez", null, null, null, null, null, null).email()).isNull();
        for (String bad : new String[] {"ana", "ana@", "@example.org", "ana@example", "a na@example.org",
                "ana@@example.org", "ana@example..org".replace("..", " .")}) {
            assertThatThrownBy(() -> guardian("Ana", "Lopez", null, null, bad, null, null, null))
                    .as(bad).isInstanceOf(InvalidGuardianException.class);
        }
        assertThatThrownBy(() -> guardian("Ana", "Lopez", null, null, "a@" + "b".repeat(250) + ".org", null, null,
                null)).isInstanceOf(InvalidGuardianException.class);
    }

    @Test
    void thePhoneIsSevenToTwentyCharactersOfDigitsSpacesParenthesesAndHyphens() {
        for (String good : new String[] {"9999888", "9999-8888", "+504 9999-8888", "(504) 2222-3333",
                "+50499998888", "12345678901234567890"}) {
            assertThat(guardian("Ana", "Lopez", null, null, null, good, null, null).phone()).as(good).isEqualTo(good);
        }
        for (String bad : new String[] {"123456", "abc12345678", "9999-8888 ext", "+", "--99998888", "99998888-",
                "123456789012345678901"}) {
            assertThatThrownBy(() -> guardian("Ana", "Lopez", null, null, null, bad, null, null))
                    .as(bad).isInstanceOf(InvalidGuardianException.class);
        }
        assertThat(guardian("Ana", "Lopez", null, null, null, "", null, null).phone()).isNull();
    }

    @Test
    void theAddressIsAtMostThreeHundredCharacters() {
        assertThat(guardian("Ana", "Lopez", null, null, null, null, "x".repeat(300), null).address()).hasSize(300);
        assertThatThrownBy(() -> guardian("Ana", "Lopez", null, null, null, null, "x".repeat(301), null))
                .isInstanceOf(InvalidGuardianException.class);
    }

    @Test
    void theChannelIsOneOfTheFourOrNoneWhenMissing() {
        for (String channel : new String[] {"EMAIL", "SMS", "WHATSAPP", "NONE"}) {
            assertThat(guardian("Ana", "Lopez", null, null, null, null, null, channel).preferredChannel().name())
                    .isEqualTo(channel);
        }
        assertThatThrownBy(() -> guardian("Ana", "Lopez", null, null, null, null, null, "FAX"))
                .isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> guardian("Ana", "Lopez", null, null, null, null, null, "sms"))
                .isInstanceOf(InvalidGuardianException.class);
    }

    @Test
    void aGuardianOnlyKnowsWhetherItHasTheDocumentAndTheRtn() {
        Guardian with = guardian("Ana", "Lopez", NationalId.of("0801-1999-12345"), Rtn.of("0801-1999-123456"), null,
                null, null, null);

        assertThat(with.hasNationalId()).isTrue();
        assertThat(with.hasRtn()).isTrue();
        assertThat(simple().hasNationalId()).isFalse();
        assertThat(simple().hasRtn()).isFalse();
    }

    @Test
    void editKeepsRemovesOrReplacesTheDocumentAndTheRtnIndependently() {
        Guardian with = guardian("Ana", "Lopez", NationalId.of("0801199912345"), Rtn.of("08011999123456"), null, null,
                null, null);

        Guardian kept = with.edit("Ana", "Lopez", ProtectedValueChange.ofNationalId(null),
                ProtectedValueChange.ofRtn(null), null, null, null, null, "ACTIVE", LATER);
        assertThat(kept.hasNationalId()).isTrue();
        assertThat(kept.hasRtn()).isTrue();

        Guardian removedDocument = with.edit("Ana", "Lopez", ProtectedValueChange.ofNationalId("  "),
                ProtectedValueChange.ofRtn(null), null, null, null, null, "ACTIVE", LATER);
        assertThat(removedDocument.hasNationalId()).isFalse();
        assertThat(removedDocument.hasRtn()).isTrue();

        Guardian removedRtn = with.edit("Ana", "Lopez", ProtectedValueChange.ofNationalId(null),
                ProtectedValueChange.ofRtn(""), null, null, null, null, "ACTIVE", LATER);
        assertThat(removedRtn.hasNationalId()).isTrue();
        assertThat(removedRtn.hasRtn()).isFalse();

        Guardian replaced = simple().edit("Ana", "Lopez", ProtectedValueChange.ofNationalId("0801199912345"),
                ProtectedValueChange.ofRtn("0801-1999-123456"), null, null, null, null, "ACTIVE", LATER);
        assertThat(replaced.hasNationalId()).isTrue();
        assertThat(replaced.hasRtn()).isTrue();
    }

    @Test
    void editKeepsTheIdentifierTheCodeAndTheCreationInstantAndMovesTheUpdateInstant() {
        Guardian before = simple();

        Guardian after = before.edit("Eva", "Mejia", ProtectedValueChange.ofNationalId(null),
                ProtectedValueChange.ofRtn(null), "eva@example.org", "99998888", "Casa 1", "SMS", "INACTIVE", LATER);

        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.guardianCode()).isEqualTo("P-000001");
        assertThat(after.createdAt()).isEqualTo(NOW);
        assertThat(after.updatedAt()).isEqualTo(LATER);
        assertThat(after.status()).isEqualTo(GuardianStatus.INACTIVE);
        assertThat(after.active()).isFalse();
        assertThat(after.firstName()).isEqualTo("Eva");
        assertThat(after.preferredChannel()).isEqualTo(PreferredChannel.SMS);
    }

    @Test
    void editRefusesAnUnknownStatusAndAMalformedDocument() {
        assertThatThrownBy(() -> simple().edit("Ana", "Lopez", ProtectedValueChange.ofNationalId(null),
                ProtectedValueChange.ofRtn(null), null, null, null, null, "DELETED", LATER))
                .isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> simple().edit("Ana", "Lopez", ProtectedValueChange.ofNationalId(null),
                ProtectedValueChange.ofRtn(null), null, null, null, null, null, LATER))
                .isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> ProtectedValueChange.ofNationalId("123")).isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> ProtectedValueChange.ofRtn("123")).isInstanceOf(InvalidGuardianException.class);
    }

    @Test
    void theNationalIdIsNormalisedAndTheRtnNeedsFourteenDigits() {
        assertThat(NationalId.of(" 0801-1999-12345 ").normalized()).isEqualTo("0801199912345");
        assertThat(NationalId.of("ab-cd-ef-gh").normalized()).isEqualTo("ABCDEFGH");
        assertThatThrownBy(() -> NationalId.of("1234567")).isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> NationalId.of("123456789012345678901")).isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> NationalId.of("1234 5678")).isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> NationalId.of(null)).isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> NationalId.of("--------")).isInstanceOf(InvalidGuardianException.class);

        assertThat(Rtn.of("0801-1999-123456").digits()).isEqualTo("08011999123456");
        assertThat(Rtn.of(" 08011999123456 ").digits()).isEqualTo("08011999123456");
        assertThatThrownBy(() -> Rtn.of("0801-1999-12345")).isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> Rtn.of("0801-1999-1234567")).isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> Rtn.of("0801-1999-12345A")).isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> Rtn.of(null)).isInstanceOf(InvalidGuardianException.class);
    }

    @Test
    void nothingThatHoldsPersonalDataPrintsIt() {
        NationalId id = NationalId.of("0801-1999-12345");
        Rtn rtn = Rtn.of("0801-1999-123456");
        Guardian guardian = guardian("Ana", "Lopez", id, rtn, "ana@example.org", "99998888", "Casa 1", null);

        assertThat(id.toString()).doesNotContain("0801").doesNotContain("12345");
        assertThat(rtn.toString()).doesNotContain("0801").doesNotContain("123456");
        assertThat(guardian.toString()).isEqualTo("Guardian[id=" + guardian.id() + "]");
        assertThat(new ProtectedValueChange.Replace<>(id).toString()).doesNotContain("0801");
        assertThatThrownBy(() -> NationalId.of("0801-19")).hasMessageNotContaining("0801");
        assertThatThrownBy(() -> Rtn.of("0801-1999")).hasMessageNotContaining("0801");
    }

    @Test
    void theCodesAreSixDigitsFromTheSequence() {
        assertThat(GuardianCode.generated(1)).isEqualTo("P-000001");
        assertThat(GuardianCode.generated(999999)).isEqualTo("P-999999");
        assertThat(GuardianCode.generated(1000000)).isEqualTo("P-1000000");
        assertThatThrownBy(() -> GuardianCode.generated(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRelationshipStatusAndChannelParsersAreExact() {
        assertThat(Relationship.parse("LEGAL_GUARDIAN")).isEqualTo(Relationship.LEGAL_GUARDIAN);
        assertThatThrownBy(() -> Relationship.parse("mother")).isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> Relationship.parse(null)).isInstanceOf(InvalidGuardianException.class);
        assertThat(GuardianStatus.parse("INACTIVE")).isEqualTo(GuardianStatus.INACTIVE);
        assertThatThrownBy(() -> GuardianStatus.parse("active")).isInstanceOf(InvalidGuardianException.class);
        assertThat(PreferredChannel.parseOrNone(null)).isEqualTo(PreferredChannel.NONE);
    }
}
