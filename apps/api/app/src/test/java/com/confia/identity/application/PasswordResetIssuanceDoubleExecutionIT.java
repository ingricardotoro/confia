package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.PlainPasswordResetToken;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * At-least-once delivery against the issuance handler (ADR-0016 compliance 5; scenarios I234, I235
 * and BJ19 of background-jobs-with-db-scheduler): running the same task twice leaves one open token and
 * the last link delivered is the only valid one, and the fourth issuance within the hour is skipped
 * without failing the task.
 */
class PasswordResetIssuanceDoubleExecutionIT extends PasswordResetIssuanceFixtures {

    /** Scenarios I234 and BJ19: two tokens issued, the first superseded, only the second open. */
    @Test
    void runningTheSameTaskTwiceLeavesOneOpenTokenAndTheLastLinkIsTheOnlyValidOne() {
        UUID account = seedAccount(INSTITUTION_A, ANA);
        PasswordResetIssuanceJob payload = new PasswordResetIssuanceJob(account);
        var context = contextOf(INSTITUTION_A, account, 1);

        clock.set("2026-10-25T10:00:05Z");
        handler().handle(payload, context);
        clock.set("2026-10-25T10:00:20Z");
        handler().handle(payload, context);

        assertThat(sender.sent).hasSize(2);
        PlainPasswordResetToken first = sender.sent.get(0);
        PlainPasswordResetToken second = sender.sent.get(1);
        assertThat(tokenCount(INSTITUTION_A, account)).as("two tokens were issued").isEqualTo(2);
        assertThat(openTokenCount(INSTITUTION_A, account)).as("and only one is open").isEqualTo(1);
        assertThat(isSuperseded(INSTITUTION_A, first)).as("the first is superseded").isTrue();
        assertThat(isOpen(INSTITUTION_A, first)).as("so it is rejected if presented").isFalse();
        assertThat(isOpen(INSTITUTION_A, second)).as("the last link delivered is the valid one").isTrue();
        assertThat(lastSent()).isEqualTo(second);
    }

    /** Scenario I235: the fourth issuance in the hour is skipped; the task ends normally. */
    @Test
    void theFourthIssuanceWithinTheHourIsSkippedWithoutFailingTheTask() {
        UUID account = seedAccount(INSTITUTION_A, ANA);
        for (String at : new String[] {"2026-10-25T10:00:00Z", "2026-10-25T10:10:00Z",
                "2026-10-25T10:20:00Z"}) {
            clock.set(at);
            run(INSTITUTION_A, account);
        }
        PlainPasswordResetToken tenTwenty = lastSent();
        int deliveries = sender.sent.size();

        clock.set("2026-10-25T10:30:00Z");
        run(INSTITUTION_A, account);

        assertThat(sender.sent).as("no call to the sender").hasSize(deliveries);
        assertThat(tokenCount(INSTITUTION_A, account)).as("no token was issued").isEqualTo(3);
        assertThat(isOpen(INSTITUTION_A, tenTwenty)).as("the 10:20 token is still live").isTrue();
        assertThat(auditActions(INSTITUTION_A)).as("the audit trail records the skipped issuance once")
                .containsOnlyOnce("identity.password_reset.issuance_skipped");
    }
}
