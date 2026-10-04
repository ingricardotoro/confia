package com.confia.bootstrap;

import static com.confia.bootstrap.BootFailureAssertions.assertNoSecretFragment;
import static com.confia.bootstrap.BootFailureAssertions.chainOf;
import static com.confia.bootstrap.BootFailureAssertions.stackTraceOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Specs/build-integrity of web-edge-foundations, decision 3: each identity secret of the
 * administrative process is read at startup and stops the process when it is missing or wrong,
 * with a message that names the property and never repeats any part of the value. Every case
 * starts the real process through the production launcher, so a failure that happened for another
 * reason would not name the property under test.
 *
 * <p>The rejected values below are deliberately unlike any word of the messages the code writes,
 * so a fragment found in a trace can only have come from the value. None of them is a secret: each
 * is invalid by construction (not Base64, not a UUID, or the wrong length).
 */
class IdentityConfigurationSecretsTest {

    private static final String NOT_BASE64 = "Tf8%Hd3^Vb6&Nc1*";
    private static final String NOT_A_UUID = "Qx7!Rk2#Jv9%Wm5@";

    /** 16 bytes from a fixed seed: valid Base64, but not the 32 bytes a pepper must have. */
    private static final String SHORT_PEPPER = shortPepper();

    @Test
    void aMissingPepperStopsTheAdminProcessAndNamesTheProperty() {
        assertThatThrownBy(() -> launchAdmin(TestProcessArguments.adminWithout(
                TestProcessArguments.PEPPER_PROPERTY)))
                .satisfies(failure -> {
                    assertThat(stackTraceOf(failure))
                            .contains(TestProcessArguments.PEPPER_PROPERTY);
                    assertThat(chainOf(failure)).anyMatch(IllegalStateException.class::isInstance);
                    assertNoSecretFragment(failure, "");
                });
    }

    @Test
    void aBlankPepperIsTreatedAsMissing() {
        assertThatThrownBy(() -> launchAdmin(TestProcessArguments.adminWith(
                TestProcessArguments.PEPPER_PROPERTY, "   ")))
                .satisfies(failure -> {
                    assertThat(stackTraceOf(failure))
                            .contains(TestProcessArguments.PEPPER_PROPERTY);
                    assertNoSecretFragment(failure, "");
                });
    }

    @Test
    void aPepperThatIsNotBase64StopsTheProcessWithoutEchoingAnyPartOfIt() {
        assertThatThrownBy(() -> launchAdmin(TestProcessArguments.adminWith(
                TestProcessArguments.PEPPER_PROPERTY, NOT_BASE64)))
                .satisfies(failure -> {
                    assertThat(stackTraceOf(failure))
                            .contains(TestProcessArguments.PEPPER_PROPERTY);
                    assertNoSecretFragment(failure, NOT_BASE64);
                });
    }

    @Test
    void aPepperOfTheWrongLengthStopsTheProcessWithoutEchoingAnyPartOfIt() {
        assertThatThrownBy(() -> launchAdmin(TestProcessArguments.adminWith(
                TestProcessArguments.PEPPER_PROPERTY, SHORT_PEPPER)))
                .satisfies(failure -> {
                    assertThat(stackTraceOf(failure))
                            .contains(TestProcessArguments.PEPPER_PROPERTY);
                    assertNoSecretFragment(failure, SHORT_PEPPER);
                });
    }

    @Test
    void aMissingLoginInstitutionStopsTheAdminProcessAndNamesTheProperty() {
        assertThatThrownBy(() -> launchAdmin(TestProcessArguments.adminWithout(
                TestProcessArguments.INSTITUTION_PROPERTY)))
                .satisfies(failure -> {
                    assertThat(stackTraceOf(failure))
                            .contains(TestProcessArguments.INSTITUTION_PROPERTY);
                    assertThat(chainOf(failure)).anyMatch(IllegalStateException.class::isInstance);
                    assertNoSecretFragment(failure, "");
                });
    }

    @Test
    void aLoginInstitutionThatIsNotAUuidStopsTheProcessWithoutEchoingAnyPartOfIt() {
        assertThatThrownBy(() -> launchAdmin(TestProcessArguments.adminWith(
                TestProcessArguments.INSTITUTION_PROPERTY, NOT_A_UUID)))
                .satisfies(failure -> {
                    assertThat(stackTraceOf(failure))
                            .contains(TestProcessArguments.INSTITUTION_PROPERTY);
                    assertNoSecretFragment(failure, NOT_A_UUID);
                });
    }

    private static void launchAdmin(String[] arguments) {
        ConfiaApplication.launch(arguments, "admin");
    }

    private static String shortPepper() {
        byte[] bytes = new byte[16];
        new Random(20261004L).nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
