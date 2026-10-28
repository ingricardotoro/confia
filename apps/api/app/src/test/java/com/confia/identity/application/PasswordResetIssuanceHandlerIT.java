package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.audit.AuditRowSnapshot;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The handler of the password-reset issuance job against a real PostgreSQL (scenarios I232, I233 and
 * I236 of background-jobs-with-db-scheduler): the token exists only when the task runs, it belongs to
 * the institution of the task and is audited as the {@code system} actor, and an account that is
 * gone gets no token and no delivery and does not fail the task.
 */
class PasswordResetIssuanceHandlerIT extends PasswordResetIssuanceFixtures {

    /** Scenario I232: time alone issues nothing; the execution issues exactly one open token. */
    @Test
    void theTokenExistsOnlyWhenTheTaskRuns() {
        UUID account = seedAccount(INSTITUTION_A, ANA);

        assertThat(tokenCount(INSTITUTION_A, account)).as("scheduled and not yet run").isZero();
        assertThat(sender.calls).isEmpty();

        run(INSTITUTION_A, account);

        assertThat(openTokenCount(INSTITUTION_A, account)).isEqualTo(1);
        assertThat(sender.sent).as("the link is delivered once").hasSize(1);
        assertThat(auditActions(INSTITUTION_A)).containsExactly("identity.password_reset.issued");
    }

    /** Scenario I233: the token and its audit entry belong to the task's institution, as {@code system}. */
    @Test
    void theTokenAndItsAuditEntryBelongToTheInstitutionOfTheTaskAndTheActorIsSystem() {
        UUID inA = seedAccount(INSTITUTION_A, ANA);
        UUID inB = seedAccount(INSTITUTION_B, "luis.mejia@colegio.edu.hn");

        run(INSTITUTION_A, inA);

        assertThat(openTokenCount(INSTITUTION_A, inA)).isEqualTo(1);
        assertThat(tokenCountOfInstitution(INSTITUTION_B)).as("the other institution gets nothing").isZero();
        assertThat(tokenCount(INSTITUTION_B, inB)).isZero();
        assertThat(auditRows(INSTITUTION_A)).singleElement().satisfies(row -> {
            assertThat(row.institutionId()).isEqualTo(INSTITUTION_A);
            assertThat(row.actorKind()).isEqualTo("system");
            assertThat(row.action()).isEqualTo("identity.password_reset.issued");
        });
        assertThat(auditRows(INSTITUTION_B)).extracting(AuditRowSnapshot::action).isEmpty();
    }

    /** Scenario I236: an account that was removed before the task ran gets no token and no call. */
    @Test
    void anAccountThatNoLongerExistsGetsNoTokenNoDeliveryAndTheTaskCompletes() {
        UUID account = seedAccount(INSTITUTION_A, ANA);
        deleteAccount(INSTITUTION_A, account);

        run(INSTITUTION_A, account);

        assertThat(tokenCountOfInstitution(INSTITUTION_A)).isZero();
        assertThat(sender.sent).isEmpty();
        assertThat(auditActions(INSTITUTION_A)).isEmpty();
    }
}
