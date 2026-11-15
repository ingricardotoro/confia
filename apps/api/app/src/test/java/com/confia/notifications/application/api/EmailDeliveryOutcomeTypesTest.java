package com.confia.notifications.application.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.notifications.domain.FailureClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The two outcomes a caller sees (specs/notifications/spec.md, "Un fallo del proveedor se traduce a
 * una clase cerrada"): a receipt for an accepted send, and a failure that carries only the closed
 * failure class, never the message, the cause or the text of the provider (NT15).
 */
class EmailDeliveryOutcomeTypesTest {

    @Test
    void aReceiptMayOrMayNotCarryTheProviderMessageId() {
        assertThat(new EmailDeliveryReceipt("0100019a-2b3c@email.amazonses.com").providerMessageId())
                .isEqualTo("0100019a-2b3c@email.amazonses.com");
        assertThat(new EmailDeliveryReceipt(null).providerMessageId()).isNull();
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {"", "has space", "line\nbreak", "semi;colon", "ana.martinez@colegio.edu.hn,x"})
    void aProviderMessageIdOutsideTheSafeAlphabetIsRejectedWithoutEchoingIt(String id) {
        assertThatThrownBy(() -> new EmailDeliveryReceipt(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("colegio");
    }

    @Test
    void theProviderMessageIdIsBoundedTo255Characters() {
        assertThatCode(() -> new EmailDeliveryReceipt("m".repeat(255))).doesNotThrowAnyException();
        assertThatThrownBy(() -> new EmailDeliveryReceipt("m".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFailureCarriesOnlyItsClassAndNoCauseOrProviderText() {
        EmailDeliveryFailedException failure = new EmailDeliveryFailedException(FailureClass.REJECTED);

        assertThat(failure.failureClass()).isEqualTo(FailureClass.REJECTED);
        assertThat(failure.getMessage()).isEqualTo("email delivery failed: rejected");
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
    }

    @Test
    void aFailureWithoutAClassIsRejected() {
        assertThatThrownBy(() -> new EmailDeliveryFailedException(null))
                .isInstanceOf(NullPointerException.class);
    }
}
