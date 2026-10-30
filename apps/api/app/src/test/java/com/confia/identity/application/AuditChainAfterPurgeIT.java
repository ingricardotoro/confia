package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import com.confia.shared.audit.AuditChainVerification;
import com.confia.shared.audit.CanonicalAuditRowSerializer;
import com.confia.shared.audit.DefaultAuditChainVerifier;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Scenario AT22 of audit-trail (background-jobs-with-db-scheduler design.md decision 17): the entries a
 * purge writes, through the real writer and the chaining trigger, leave the hash chain of the
 * institution intact when the chain verifier walks it.
 */
class AuditChainAfterPurgeIT extends IdentityPurgeFixtures {

    @Test
    void theChainStillVerifiesAfterTheEntriesOfBothPurgesAndTheClearing() {
        Instant now = databaseNow();
        UUID account = seedPlainAccount(institutionId);
        seedResetTokens(institutionId, account, 6_000, now.minus(Duration.ofHours(24)).minusSeconds(1));
        seedConsumedWithResidualSuccessor(institutionId, now.minus(Duration.ofHours(1)));

        resetPurge(fixedAt(now)).runFor(institutionId, runOf(PurgeExpiredPasswordResetTokens.TASK_NAME));
        refreshPurge(fixedAt(now)).runFor(institutionId, runOf(PurgeExpiredRefreshTokens.TASK_NAME));

        assertThat(auditRows()).as("two reset batches, the clearing and one family batch").hasSize(4);
        AuditChainVerification result = new DefaultAuditChainVerifier(transactionRunner(),
                new JooqAuditLogReader(dsl), new CanonicalAuditRowSerializer())
                .verifyChainOf(institutionId);
        assertThat(result).asInstanceOf(type(AuditChainVerification.Intact.class)).satisfies(intact -> {
            assertThat(intact.institutionId()).isEqualTo(institutionId);
            assertThat(intact.verifiedRows()).isEqualTo(4L);
        });
    }
}
