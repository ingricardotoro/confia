package com.confia.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import com.confia.notifications.application.api.TransactionalEmailTemplate;
import com.confia.notifications.domain.FailureClass;
import com.confia.notifications.domain.RecipientHash;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The value the use case hands to the delivery log (transactional-email-adapter design.md decision 4,
 * steps 5 and 6): a {@code sent} attempt never carries a failure class and a {@code failed} one
 * always does and never carries a provider identifier, the same coherence the {@code
 * notifications_email_delivery_outcome_chk} constraint enforces, so an impossible row is refused
 * before it reaches the database.
 */
class EmailDeliveryAttemptTest {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final UUID ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-11-10T08:00:00Z");
    private static final RecipientHash HASH = new RecipientHash("0f".repeat(32));
    private static final TransactionalEmailTemplate TEMPLATE =
            TransactionalEmailTemplate.PASSWORD_RESET_LINK;

    @Test
    void aSentAttemptCarriesTheProviderIdentifierAndNoFailureClass() {
        EmailDeliveryAttempt attempt = EmailDeliveryAttempt.sent(INSTITUTION, ID, REQUEST_ID, AT,
                TEMPLATE, HASH, "ses-123");

        assertThat(attempt.status()).isEqualTo(EmailDeliveryAttempt.Status.SENT);
        assertThat(attempt.providerMessageId()).isEqualTo("ses-123");
        assertThat(attempt.failureClass()).isNull();
        assertThat(attempt.institutionId()).isEqualTo(INSTITUTION);
        assertThat(attempt.id()).isEqualTo(ID);
        assertThat(attempt.requestId()).isEqualTo(REQUEST_ID);
        assertThat(attempt.attemptedAt()).isEqualTo(AT);
        assertThat(attempt.template()).isEqualTo(TEMPLATE);
        assertThat(attempt.recipient()).isEqualTo(HASH);
    }

    @Test
    void aSentAttemptMayHaveNoProviderIdentifier() {
        assertThat(EmailDeliveryAttempt.sent(INSTITUTION, ID, REQUEST_ID, AT, TEMPLATE, HASH, null)
                .providerMessageId()).isNull();
    }

    @Test
    void aFailedAttemptCarriesItsClassAndNoProviderIdentifier() {
        EmailDeliveryAttempt attempt = EmailDeliveryAttempt.failed(INSTITUTION, ID, REQUEST_ID, AT,
                TEMPLATE, HASH, FailureClass.TIMEOUT);

        assertThat(attempt.status()).isEqualTo(EmailDeliveryAttempt.Status.FAILED);
        assertThat(attempt.failureClass()).isEqualTo(FailureClass.TIMEOUT);
        assertThat(attempt.providerMessageId()).isNull();
    }

    @Test
    void theCanonicalConstructorRefusesIncoherentCombinations() {
        assertThatThrownBy(() -> new EmailDeliveryAttempt(INSTITUTION, ID, REQUEST_ID, AT, TEMPLATE,
                HASH, EmailDeliveryAttempt.Status.SENT, null, FailureClass.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailDeliveryAttempt(INSTITUTION, ID, REQUEST_ID, AT, TEMPLATE,
                HASH, EmailDeliveryAttempt.Status.FAILED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailDeliveryAttempt(INSTITUTION, ID, REQUEST_ID, AT, TEMPLATE,
                HASH, EmailDeliveryAttempt.Status.FAILED, "ses-123", FailureClass.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankProviderIdentifierIsRefusedRatherThanStored() {
        assertThatThrownBy(() -> EmailDeliveryAttempt.sent(INSTITUTION, ID, REQUEST_ID, AT,
                TEMPLATE, HASH, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyRequiredComponentIsChecked() {
        assertThatNullPointerException().isThrownBy(() -> EmailDeliveryAttempt.sent(null, ID,
                REQUEST_ID, AT, TEMPLATE, HASH, null));
        assertThatNullPointerException().isThrownBy(() -> EmailDeliveryAttempt.sent(INSTITUTION,
                null, REQUEST_ID, AT, TEMPLATE, HASH, null));
        assertThatNullPointerException().isThrownBy(() -> EmailDeliveryAttempt.sent(INSTITUTION, ID,
                null, AT, TEMPLATE, HASH, null));
        assertThatNullPointerException().isThrownBy(() -> EmailDeliveryAttempt.sent(INSTITUTION, ID,
                REQUEST_ID, null, TEMPLATE, HASH, null));
        assertThatNullPointerException().isThrownBy(() -> EmailDeliveryAttempt.sent(INSTITUTION, ID,
                REQUEST_ID, AT, null, HASH, null));
        assertThatNullPointerException().isThrownBy(() -> EmailDeliveryAttempt.sent(INSTITUTION, ID,
                REQUEST_ID, AT, TEMPLATE, null, null));
        assertThatNullPointerException().isThrownBy(() -> EmailDeliveryAttempt.failed(INSTITUTION,
                ID, REQUEST_ID, AT, TEMPLATE, HASH, null));
    }
}
