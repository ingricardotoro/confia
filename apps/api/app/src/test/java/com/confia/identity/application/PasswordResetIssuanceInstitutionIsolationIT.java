package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.StaffAccountId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The issuance task is bound to the institution it was scheduled for (scenarios I268 and I233 of
 * background-jobs-with-db-scheduler; ADR-0016 rule 4): an account id that belongs to another institution ends
 * as "not found" with no token and no audit entry in either, and the handler never acts in an
 * institution other than the task's, even when both institutions hold an account with the same id.
 */
class PasswordResetIssuanceInstitutionIsolationIT extends PasswordResetIssuanceFixtures {

    /** Scenario I268: the account belongs to B, the task to A. */
    @Test
    void aTaskOfOneInstitutionWithAnAccountOfAnotherEndsAsNotFoundWithNoEffect() {
        UUID accountOfB = seedAccount(INSTITUTION_B, ANA);
        var context = contextOf(INSTITUTION_A, accountOfB, 1);

        IssuePasswordResetTokenDecision decision = issuance().execute(context.systemContext(),
                new StaffAccountId(accountOfB));
        handler().handle(new PasswordResetIssuanceJob(accountOfB), context);

        assertThat(decision).isEqualTo(IssuePasswordResetTokenDecision.ACCOUNT_NOT_FOUND);
        assertThat(tokenCountOfInstitution(INSTITUTION_B)).as("no token in B").isZero();
        assertThat(tokenCountOfInstitution(INSTITUTION_A)).as("no token in A").isZero();
        assertThat(auditActions(INSTITUTION_B)).as("no audit entry in B").isEmpty();
        assertThat(auditActions(INSTITUTION_A)).as("no audit entry in A").isEmpty();
        assertThat(sender.sent).isEmpty();
    }

    /**
     * Scenario I233, the sharp edge: both institutions hold an account with the same id, and the task
     * of A must reach only A's. A handler that acted in B would issue the token there.
     */
    @Test
    void whenBothInstitutionsHoldTheSameAccountIdOnlyTheTasksInstitutionGetsTheToken() {
        UUID shared = UUID.randomUUID();
        seedAccount(INSTITUTION_A, shared, ANA);
        seedAccount(INSTITUTION_B, shared, "luis.mejia@colegio.edu.hn");

        run(INSTITUTION_A, shared);

        assertThat(openTokenCount(INSTITUTION_A, shared)).as("the task's institution").isEqualTo(1);
        assertThat(tokenCount(INSTITUTION_B, shared)).as("the other institution").isZero();
        assertThat(auditActions(INSTITUTION_B)).isEmpty();
        assertThat(sender.sent).hasSize(1);
    }
}
