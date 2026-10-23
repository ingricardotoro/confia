package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * {@link FailureSpreadWindow} (session-endpoints-cookie-and-csrf design.md decision 12; scenarios
 * I164, I165, I166 and I224): five distinct identifiers failing from one address inside ten minutes
 * raise the signal once, a failure is live while it is strictly younger than ten minutes, and the
 * same identifier failing again adds nothing.
 */
class FailureSpreadWindowTest {

    private static final Instant TEN = Instant.parse("2026-10-12T10:00:00Z");

    private final FailureSpreadWindow window = new FailureSpreadWindow();

    @Test
    void thresholdAndWindowAreFiveIdentifiersAndTenMinutes() {
        assertThat(FailureSpreadWindow.THRESHOLD).isEqualTo(5);
        assertThat(FailureSpreadWindow.WINDOW).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void theFifthDistinctIdentifierRaisesTheSignalAndTheFirstFourDoNot() {
        assertThat(window.record(fingerprint(1), TEN)).isFalse();
        assertThat(window.record(fingerprint(2), TEN.plusSeconds(60))).isFalse();
        assertThat(window.record(fingerprint(3), TEN.plusSeconds(120))).isFalse();
        assertThat(window.record(fingerprint(4), TEN.plusSeconds(300))).isFalse();

        assertThat(window.record(fingerprint(5), TEN.plusSeconds(360))).isTrue();
    }

    @Test
    void fiveFailuresOfTheSameIdentifierNeverRaiseTheSignal() {
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(window.record(fingerprint(1), TEN.plusSeconds(attempt))).isFalse();
        }
        assertThat(window.record(fingerprint(1), TEN.plusSeconds(10))).isFalse();
    }

    @Test
    void anIdentifierRepeatedBeforeTheFifthDoesNotCountTwice() {
        window.record(fingerprint(1), TEN);
        window.record(fingerprint(2), TEN);
        window.record(fingerprint(3), TEN);
        window.record(fingerprint(4), TEN);

        assertThat(window.record(fingerprint(4), TEN.plusSeconds(1)))
                .as("the repeat of a counted identifier is not a fifth identifier").isFalse();
        assertThat(window.record(fingerprint(5), TEN.plusSeconds(2))).isTrue();
    }

    @Test
    void aFailureOlderThanTenMinutesNoLongerCountsAtTheExactEdge() {
        for (int identifier = 1; identifier <= 4; identifier++) {
            window.record(fingerprint(identifier), TEN);
        }

        assertThat(window.record(fingerprint(5), TEN.plus(Duration.ofMinutes(10))))
                .as("the failure of 10:00:00 is ten minutes old at 10:10:00: it is not live").isFalse();
    }

    @Test
    void aFailureOneNanosecondYoungerThanTenMinutesStillCounts() {
        for (int identifier = 1; identifier <= 4; identifier++) {
            window.record(fingerprint(identifier), TEN);
        }

        assertThat(window.record(fingerprint(5), TEN.plus(Duration.ofMinutes(10)).minusNanos(1)))
                .isTrue();
    }

    @Test
    void theSixthDistinctIdentifierDoesNotRaiseASecondSignalWhileTheWindowHolds() {
        for (int identifier = 1; identifier <= 5; identifier++) {
            window.record(fingerprint(identifier), TEN.plusSeconds(identifier));
        }

        assertThat(window.record(fingerprint(6), TEN.plusSeconds(10))).isFalse();
        assertThat(window.record(fingerprint(7), TEN.plusSeconds(11))).isFalse();
    }

    @Test
    void repeatingAnAlreadyCountedIdentifierAfterTheSignalDoesNotSignalAgain() {
        for (int identifier = 1; identifier <= 5; identifier++) {
            window.record(fingerprint(identifier), TEN.plusSeconds(identifier));
        }
        window.record(fingerprint(6), TEN.plusSeconds(10));

        assertThat(window.record(fingerprint(3), TEN.plusSeconds(20))).isFalse();
        assertThat(window.record(fingerprint(5), TEN.plusSeconds(21))).isFalse();
    }

    @Test
    void aRepeatedFailureRefreshesTheInstantOfThatIdentifier() {
        window.record(fingerprint(1), TEN);
        window.record(fingerprint(1), TEN.plus(Duration.ofMinutes(8)));
        window.record(fingerprint(2), TEN.plus(Duration.ofMinutes(9)));
        window.record(fingerprint(3), TEN.plus(Duration.ofMinutes(9)));
        window.record(fingerprint(4), TEN.plus(Duration.ofMinutes(9)));

        assertThat(window.record(fingerprint(5), TEN.plus(Duration.ofMinutes(11))))
                .as("the first identifier failed again at 10:08, so it is three minutes old")
                .isTrue();
    }

    @Test
    void aWindowThatEmptiedByAgeRaisesTheSignalAgainForFiveNewIdentifiers() {
        for (int identifier = 1; identifier <= 5; identifier++) {
            window.record(fingerprint(identifier), TEN);
        }
        Instant later = TEN.plus(Duration.ofMinutes(30));

        for (int identifier = 11; identifier <= 14; identifier++) {
            assertThat(window.record(fingerprint(identifier), later)).isFalse();
        }
        assertThat(window.record(fingerprint(15), later)).isTrue();
    }

    @Test
    void theSignalReturnsWhenExpiryTakesTheCountBelowTheThresholdAndANewOneArrives() {
        window.record(fingerprint(1), TEN);
        for (int identifier = 2; identifier <= 5; identifier++) {
            window.record(fingerprint(identifier), TEN.plus(Duration.ofMinutes(5)));
        }

        assertThat(window.record(fingerprint(6), TEN.plus(Duration.ofMinutes(11))))
                .as("the first failure aged out, so four are live and the sixth identifier makes five")
                .isTrue();
        assertThat(window.record(fingerprint(7), TEN.plus(Duration.ofMinutes(11)))).isFalse();
    }

    @Test
    void hasLiveFailuresIsTrueOnlyWhileAFailureIsStrictlyYoungerThanTenMinutes() {
        assertThat(window.hasLiveFailures(TEN)).as("a fresh window holds nothing").isFalse();
        window.record(fingerprint(1), TEN);

        assertThat(window.hasLiveFailures(TEN)).isTrue();
        assertThat(window.hasLiveFailures(TEN.plus(Duration.ofMinutes(10)).minusNanos(1))).isTrue();
        assertThat(window.hasLiveFailures(TEN.plus(Duration.ofMinutes(10)))).isFalse();
    }

    @Test
    void hasLiveFailuresDoesNotDisturbWhatIsRecorded() {
        window.record(fingerprint(1), TEN);
        window.hasLiveFailures(TEN.plus(Duration.ofHours(1)));

        assertThat(window.hasLiveFailures(TEN)).as("asking never drops anything").isTrue();
    }

    @Test
    void rejectsNullArguments() {
        assertThatThrownBy(() -> window.record(null, TEN))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("identifier");
        assertThatThrownBy(() -> window.record(fingerprint(1), null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("now");
        assertThatThrownBy(() -> window.hasLiveFailures(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("now");
    }

    static IdentifierFingerprint fingerprint(int number) {
        return new IdentifierFingerprint(String.format("%064x", number));
    }
}
