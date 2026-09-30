package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * {@link IssuePasswordResetTokenDecision} (password-recovery-token design.md decision 6): an
 * issuance reports whether it issued, never the token, which leaves only through the link sender.
 * The commit-then-send order needs a real transaction and is {@code IssuePasswordResetTokenIT}'s.
 */
class IssuePasswordResetTokenTest {

    @Test
    void theDecisionNamesTheOutcomeAndCarriesNoToken() {
        assertThat(IssuePasswordResetTokenDecision.class.isEnum()).isTrue();
        assertThat(Arrays.stream(IssuePasswordResetTokenDecision.values()).map(Enum::name))
                .containsExactly("ISSUED", "SKIPPED", "ACCOUNT_NOT_FOUND");
        assertThat(IssuePasswordResetTokenDecision.class.getDeclaredFields())
                .filteredOn(field -> !field.isSynthetic())
                .allSatisfy(field -> assertThat(field.getType())
                        .isEqualTo(IssuePasswordResetTokenDecision.class)
                        .as("only the enum constants themselves"));
    }
}
