package com.confia.charges.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The rules of the void of a charge and of the integrity run that live in the domain of {@code charges}. */
class ChargeVoidRulesTest {

    private static final String TWENTY = "x".repeat(20);

    // --- VoidReason: 20 to 500 characters once stripped ---

    @Test
    void aReasonOfExactlyTheMinimumAndTheMaximumIsAccepted() {
        assertThat(VoidReason.of(TWENTY).text()).hasSize(20);
        assertThat(VoidReason.of("y".repeat(500)).text()).hasSize(500);
    }

    @Test
    void aReasonOneCharacterOutsideTheBoundsIsRefused() {
        assertThatThrownBy(() -> VoidReason.of("x".repeat(19))).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> VoidReason.of("y".repeat(501))).isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void theLengthCountsCodePointsLikeTheDatabaseCheckDoes() {
        String astral = "\uD83D\uDE00".repeat(20);
        assertThat(astral.length()).isEqualTo(40);
        assertThat(VoidReason.of(astral).text()).isEqualTo(astral);
        assertThat(VoidReason.of("\uD83D\uDE00".repeat(500)).text()).hasSize(1000);
        assertThatThrownBy(() -> VoidReason.of("\uD83D\uDE00".repeat(19))).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> VoidReason.of("\uD83D\uDE00".repeat(501))).isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void theBorderSpacesDoNotCountTowardTheMinimum() {
        assertThatThrownBy(() -> VoidReason.of("   " + "x".repeat(19) + "   "))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThat(VoidReason.of("  " + TWENTY + "  ").text()).isEqualTo(TWENTY);
    }

    @Test
    void aMissingReasonAndControlOrFormatCharactersAreRefused() {
        assertThatThrownBy(() -> VoidReason.of(null)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> VoidReason.of("tabs\tinside the reason of the void"))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> VoidReason.of("hidden​format character in the reason"))
                .isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void aReasonNeverShowsItsTextWhenPrinted() {
        VoidReason reason = VoidReason.of("Cobro duplicado de la familia Perez Lopez");

        assertThat(reason.toString()).doesNotContain("Perez").contains("length=");
    }

    // --- ChargeVoid ---

    private static ChargeVoid sample(String hash) {
        return new ChargeVoid(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Cobro duplicado de febrero",
                "key-1", hash, UUID.randomUUID(), Instant.parse("2027-02-10T15:00:00Z"));
    }

    @Test
    void aVoidAnswersOnlyTheRequestItHashes() {
        ChargeVoid charge = sample("a".repeat(64));

        assertThat(charge.answers("a".repeat(64))).isTrue();
        assertThat(charge.answers("b".repeat(64))).isFalse();
    }

    @Test
    void aVoidRequiresEveryPart() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2027-02-10T15:00:00Z");
        assertThatThrownBy(() -> new ChargeVoid(null, id, id, "r", "k", "h", id, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChargeVoid(id, null, id, "r", "k", "h", id, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChargeVoid(id, id, null, "r", "k", "h", id, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChargeVoid(id, id, id, null, "k", "h", id, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChargeVoid(id, id, id, "r", null, "h", id, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChargeVoid(id, id, id, "r", "k", null, id, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChargeVoid(id, id, id, "r", "k", "h", null, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChargeVoid(id, id, id, "r", "k", "h", id, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aVoidNeverShowsItsReasonWhenPrinted() {
        assertThat(sample("a".repeat(64)).toString()).doesNotContain("duplicado").contains("chargeId=");
    }

    // --- errors keep their published codes ---

    @Test
    void theErrorsKeepTheirPublishedCodes() {
        assertThat(new ChargeAlreadyVoidedException().code()).isEqualTo("charge-already-voided");
        assertThat(new ChargeNotVoidableException().code()).isEqualTo("charge-not-voidable");
    }

    // --- IntegrityRun ---

    @Test
    void aRunWithoutDiscrepanciesIsOkAndOneWithThemIsNot() {
        Instant start = Instant.parse("2027-02-10T08:30:00Z");

        assertThat(new IntegrityRun(UUID.randomUUID(), start, start.plusSeconds(3), 10, 20, 0).status())
                .isEqualTo(IntegrityRun.OK);
        assertThat(new IntegrityRun(UUID.randomUUID(), start, start, 10, 20, 1).status())
                .isEqualTo(IntegrityRun.DISCREPANCIES);
    }

    @Test
    void aRunCannotEndBeforeItStartsNorCountANegativeNumber() {
        Instant start = Instant.parse("2027-02-10T08:30:00Z");
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> new IntegrityRun(id, start, start.minusSeconds(1), 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrityRun(id, start, start, -1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrityRun(id, start, start, 0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrityRun(id, start, start, 0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrityRun(null, start, start, 0, 0, 0)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theExceptionThatKeepsTheNightVisibleCarriesTheInstitutionAndTheCount() {
        InstitutionId institution = new InstitutionId(UUID.randomUUID());

        IntegrityDiscrepanciesFoundException found = new IntegrityDiscrepanciesFoundException(institution, 3);

        assertThat(found.institution()).isEqualTo(institution);
        assertThat(found.discrepancies()).isEqualTo(3);
        assertThat(found.getMessage()).contains("3");
        assertThat(found.getStackTrace()).isEmpty();
    }
}
