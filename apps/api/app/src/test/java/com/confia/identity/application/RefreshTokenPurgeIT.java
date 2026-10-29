package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.application.RefreshOutcome.Rejected;
import com.confia.identity.domain.RefreshRejection;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.identity.infrastructure.JooqIdentityRetentionPurges;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.jobs.RecurringRun;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The daily purge of refresh tokens against a real PostgreSQL (scenarios I252 to I256 of identity and
 * AT20 and AT28 of audit-trail, background-jobs-with-db-scheduler design.md decision 17): the residual
 * encrypted successor is emptied once the grace window is over and not before nor at its exact border,
 * emptying it leaves reuse detection as it was, never crosses institutions and leaves an entry of its
 * own; the families are purged in batches with one entry each. A rotation that only the clock can tell
 * from a purge uses fixed instants; the family purge follows the database-clock rule.
 */
class RefreshTokenPurgeIT extends IdentityPurgeFixtures {

    private static final String TASK = PurgeExpiredRefreshTokens.TASK_NAME;
    private static final String CLEARED = PurgeExpiredRefreshTokens.ACTION_CLEARED;
    private static final String PURGED = PurgeExpiredRefreshTokens.ACTION_PURGED;
    private static final Instant CONSUMED_AT = Instant.parse("2026-10-25T10:00:00Z");

    /** Scenario I252: after the grace window the successor is gone, the row and its consumption are not. */
    @Test
    void theResidualSuccessorIsEmptiedOnceTheGraceWindowIsOverAndTheRowStays() {
        UUID consumed = seedConsumedWithResidualSuccessor(institutionId, CONSUMED_AT);

        refreshPurge(fixedAt(Instant.parse("2026-10-25T10:00:11Z"))).runFor(institutionId, runOf(TASK));

        assertThat(residualSuccessorOf(institutionId, consumed)).isNull();
        assertThat(count(institutionId, "identity_refresh_token")).as("no row is deleted").isEqualTo(2);
        assertThat(transactionRunner().execute(context(), () -> dsl.fetchSingle(
                "select consumed_at from identity_refresh_token where institution_id = ? and id = ?",
                institutionId.value(), consumed).get(0, java.time.OffsetDateTime.class).toInstant()))
                .as("the consumption mark is kept").isEqualTo(CONSUMED_AT);
    }

    /** Scenario I253: inside the window and at its exact border the successor is kept. */
    @ParameterizedTest(name = "the purge at {0} keeps the successor")
    @ValueSource(strings = {"2026-10-25T10:00:09Z", "2026-10-25T10:00:10Z"})
    void insideTheWindowAndAtTheExactBorderTheSuccessorIsKept(String purgeAt) {
        UUID consumed = seedConsumedWithResidualSuccessor(institutionId, CONSUMED_AT);

        refreshPurge(fixedAt(Instant.parse(purgeAt))).runFor(institutionId, runOf(TASK));

        assertThat(residualSuccessorOf(institutionId, consumed)).isEqualTo("v1:AAAA");
        assertThat(entries(institutionId, CLEARED)).singleElement().satisfies(entry ->
                assertThat(detail(entry).get("cleared").asLong()).isEqualTo(0L));
    }

    /** The first instant after the border is already outside the window. */
    @Test
    void aMillisecondAfterTheBorderTheSuccessorIsEmptied() {
        UUID consumed = seedConsumedWithResidualSuccessor(institutionId, CONSUMED_AT);

        refreshPurge(fixedAt(Instant.parse("2026-10-25T10:00:10.001Z"))).runFor(institutionId, runOf(TASK));

        assertThat(residualSuccessorOf(institutionId, consumed)).isNull();
    }

    /** Scenario I254: a token whose successor was emptied is still a reuse when it comes back after the grace. */
    @Test
    void emptyingTheSuccessorDoesNotTurnOffReuseDetection() {
        StaffAccountId account = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(account, false, "2026-10-25T09:00:00Z", "2026-10-25T10:00:00Z",
                "2026-10-26T09:00:00Z", null, null);
        ConsumedSeed seed = seedConsumedToken(family, "2026-10-25T09:00:00Z", "2026-10-25T17:00:00Z",
                CONSUMED_AT.toString(), AGENT_ONE, true);
        refreshPurge(fixedAt(Instant.parse("2026-10-25T10:00:11Z"))).runFor(institutionId, runOf(TASK));
        assertThat(tokensOf(family).get(0).get("successor_token_ciphertext", String.class)).isNull();

        RefreshOutcome outcome = rotate("2026-10-25T10:00:30Z", seed.consumed().value(), AGENT_ONE);

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        assertThat(familyRow(family).get("revoked_reason", String.class)).isEqualTo("reuse_detected");
    }

    /** Scenario I255: the purge of one institution leaves the residual successor of another. */
    @Test
    void emptyingTheSuccessorsOfOneInstitutionLeavesTheOtherAlone() {
        UUID mine = seedConsumedWithResidualSuccessor(institutionId, CONSUMED_AT);
        UUID foreign = seedConsumedWithResidualSuccessor(otherInstitution, CONSUMED_AT);

        refreshPurge(fixedAt(CONSUMED_AT.plus(Duration.ofHours(1)))).runFor(institutionId, runOf(TASK));

        assertThat(residualSuccessorOf(institutionId, mine)).isNull();
        assertThat(residualSuccessorOf(otherInstitution, foreign)).isEqualTo("v1:AAAA");
        assertThat(entries(otherInstitution, CLEARED)).isEmpty();
    }

    /** Scenario AT28: the clearing leaves its own entry, apart from the entries of the family batches. */
    @Test
    void theClearingLeavesItsOwnEntryApartFromTheFamilyBatchEntries() {
        for (int i = 0; i < 4; i++) {
            seedConsumedWithResidualSuccessor(institutionId, CONSUMED_AT);
        }
        RecurringRun run = runOf(TASK);

        refreshPurge(fixedAt(Instant.parse("2026-10-25T10:00:11Z"))).runFor(institutionId, run);

        assertThat(auditRows()).extracting(AuditRowSnapshot::action).containsExactly(CLEARED, PURGED);
        assertThat(entries(institutionId, CLEARED)).singleElement().satisfies(entry -> {
            assertThat(detail(entry)).isEqualTo(detail("{\"cleared\":4,\"cutoff\":\"2026-10-25T10:00:01Z\"}"));
            assertThat(entry.entityId()).isEqualTo(run.runId().toString());
            assertThat(entry.requestId()).isEqualTo(run.runId());
            assertThat(entry.actorKind()).isEqualTo("system");
            assertThat(entry.actorLabel()).isEqualTo("retention-purge");
        });
    }

    /** Scenarios AT20 and the border of the family purge: counts of families and tokens, nothing of the rows. */
    @Test
    void theFamiliesPastTheirCutoffAreDeletedWithTheirTokensAndTheEntryHoldsOnlyCountsAndInstants() {
        Instant now = databaseNow();
        Instant cutoff = now.minus(Duration.ofDays(30));
        StaffAccountId account = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        String before = cutoff.minus(Duration.ofDays(2)).toString();
        String sixtyDaysAhead = now.plus(Duration.ofDays(60)).toString();

        UUID revoked = seedFamilyFrom(account, false, before, before, sixtyDaysAhead,
                cutoff.minusSeconds(1).toString(), "password_change", "203.0.113.7");
        seedConsumedToken(revoked, before, sixtyDaysAhead, before, AGENT_ONE, false);
        UUID ended = seedFamily(account, false, before, before, cutoff.minusSeconds(1).toString(), null, null);
        seedOpenToken(ended, before, sixtyDaysAhead);
        UUID onTheBorder = seedFamily(account, false, before, before, sixtyDaysAhead, cutoff.toString(),
                "password_change");
        seedOpenToken(onTheBorder, before, sixtyDaysAhead);
        UUID live = seedFamily(account, false, before, now.toString(), sixtyDaysAhead, null, null);
        seedOpenToken(live, before, sixtyDaysAhead);

        refreshPurge(fixedAt(now)).runFor(institutionId, runOf(TASK));

        assertThat(count(institutionId, "identity_refresh_token_family")).as("the border and the live one stay")
                .isEqualTo(2);
        assertThat(count(institutionId, "identity_refresh_token")).isEqualTo(2);
        assertThat(entries(institutionId, PURGED)).singleElement().satisfies(entry -> {
            assertThat(detail(entry)).isEqualTo(detail("{\"families\":2,\"tokens\":3,\"cutoff\":\"" + cutoff + "\",\"batch\":1}"));
            assertThat(entry.afterValue()).doesNotContain("203.0.113.7")
                    .doesNotContain(UserAgentFingerprint.of(AGENT_ONE).value())
                    .doesNotContain("colegio.edu.hn").doesNotContain(revoked.toString())
                    .doesNotContain(account.value().toString());
        });
    }

    /** The batch is full when the families fill it, whatever the tokens are, and the limit and cutoffs are the caller's. */
    @Test
    void aBatchOfFullFamiliesCallsForAnotherAndTheCutoffsComeFromTheClock() {
        RecordingPurges purges = new RecordingPurges(call -> call == 2 ? 5_000 : 10);
        Instant now = Instant.parse("2026-10-25T10:30:01Z");

        refreshPurge(purges, new JooqAuditLogWriter(dsl), fixedAt(now)).runFor(institutionId, runOf(TASK));

        assertThat(purges.calls).extracting(RecordingPurges.Call::operation, RecordingPurges.Call::cutoff,
                RecordingPurges.Call::limit).containsExactly(
                org.assertj.core.api.Assertions.tuple("residualSuccessors", now.minusSeconds(10), 0),
                org.assertj.core.api.Assertions.tuple("refreshTokenFamilies", now.minus(Duration.ofDays(30)), 5_000),
                org.assertj.core.api.Assertions.tuple("refreshTokenFamilies", now.minus(Duration.ofDays(30)), 5_000));
        assertThat(entries(institutionId, PURGED)).extracting(IdentityPurgeFixtures::detail).containsExactly(
                detail("{\"families\":5000,\"tokens\":10000,\"cutoff\":\"" + now.minus(Duration.ofDays(30)) + "\",\"batch\":1}"),
                detail("{\"families\":10,\"tokens\":20,\"cutoff\":\"" + now.minus(Duration.ofDays(30)) + "\",\"batch\":2}"));
    }

    /** Scenarios I256 and I263: a second run changes no row and reports zero. */
    @Test
    void aSecondRunChangesNothingAndReportsZero() {
        seedConsumedWithResidualSuccessor(institutionId, CONSUMED_AT);
        StaffAccountId account = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        seedFamily(account, false, "2026-07-01T00:00:00Z", "2026-07-01T00:00:00Z", "2026-07-02T00:00:00Z",
                "2026-07-01T12:00:00Z", "password_change");
        PurgeExpiredRefreshTokens purge = refreshPurge(fixedAt(Instant.parse("2026-10-25T10:00:11Z")));

        purge.runFor(institutionId, runOf(TASK));
        String afterFirst = storedSessions();
        purge.runFor(institutionId, runOf(TASK));

        assertThat(storedSessions()).isEqualTo(afterFirst);
        List<AuditRowSnapshot> clearings = entries(institutionId, CLEARED);
        assertThat(clearings).hasSize(2);
        assertThat(detail(clearings.get(0)).get("cleared").asLong()).isEqualTo(1L);
        assertThat(detail(clearings.get(1)).get("cleared").asLong()).isEqualTo(0L);
        assertThat(detail(entries(institutionId, PURGED).get(1)).get("families").asLong()).isZero();
    }

    /** The clearing is its own transaction: when the batches fail, the clearing and its entry are committed. */
    @Test
    void aFailureInTheFamilyBatchesLeavesTheClearingCommittedWithItsEntry() {
        UUID first = seedConsumedWithResidualSuccessor(institutionId, CONSUMED_AT);
        UUID second = seedConsumedWithResidualSuccessor(institutionId, CONSUMED_AT);
        AuditLogWriter failsOnTheBatch = entry -> {
            new JooqAuditLogWriter(dsl).append(entry);
            if (entry.action().equals(PURGED)) {
                throw new IllegalStateException("the batch is lost");
            }
        };

        assertThatThrownBy(() -> refreshPurge(new JooqIdentityRetentionPurges(dsl), failsOnTheBatch,
                fixedAt(Instant.parse("2026-10-25T10:00:11Z"))).runFor(institutionId, runOf(TASK)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(residualSuccessorOf(institutionId, first)).isNull();
        assertThat(residualSuccessorOf(institutionId, second)).isNull();
        assertThat(entries(institutionId, CLEARED)).singleElement().satisfies(entry ->
                assertThat(detail(entry).get("cleared").asLong()).isEqualTo(2L));
        assertThat(entries(institutionId, PURGED)).as("the batch entry went with its batch").isEmpty();
    }

}
