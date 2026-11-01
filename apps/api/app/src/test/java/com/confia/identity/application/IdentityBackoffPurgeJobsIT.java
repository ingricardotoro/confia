package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.infrastructure.JooqIdentityRetentionPurges;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.jobs.PurgeProbeRows;
import com.confia.shared.jobs.RecurringRun;
import com.confia.shared.observability.events.OperationalEventLog;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The daily purges of idle login and TOTP backoff rows against a real PostgreSQL (scenarios I262, I263
 * and I274 of identity, AT18 to AT21 of audit-trail, design.md decision 17): one handler, one table and
 * one action for each kind, the border of the cutoff exact (the clock is fixed at {@code now()} of
 * PostgreSQL, read once), the entry of each batch with its count and nothing of the rows, another
 * institution untouched, and a second run that changes nothing and does not fail.
 */
class IdentityBackoffPurgeJobsIT extends IdentityPurgeFixtures {

    private static final Duration WINDOW = Duration.ofHours(24);

    private static String tableOf(PurgeStaleBackoff.Kind kind) {
        return kind == PurgeStaleBackoff.Kind.LOGIN ? "identity_login_backoff" : "identity_mfa_totp_backoff";
    }

    private void seed(PurgeStaleBackoff.Kind kind, UUID institution, int count, Instant lastAttemptAt) {
        if (kind == PurgeStaleBackoff.Kind.LOGIN) {
            PurgeProbeRows.loginBackoff(institution, count, lastAttemptAt);
        } else {
            PurgeProbeRows.mfaTotpBackoff(institution, count, lastAttemptAt);
        }
    }

    private PurgeStaleBackoff purge(PurgeStaleBackoff.Kind kind, IdentityRetentionPurges purges, Instant now) {
        return new PurgeStaleBackoff(kind, transactionRunner(), purges, new JooqAuditLogWriter(dsl),
                new OperationalEventLog(), fixedAt(now));
    }

    private PurgeStaleBackoff purge(PurgeStaleBackoff.Kind kind, Instant now) {
        return purge(kind, new JooqIdentityRetentionPurges(dsl), now);
    }

    private long rows(PurgeStaleBackoff.Kind kind, UUID institution) {
        return PurgeProbeRows.count(tableOf(kind), institution);
    }

    /** Scenarios I262 and BJ62: each kind has the name and the action written in the change. */
    @Test
    void eachKindHasItsTaskNameAndItsAction() {
        assertThat(PurgeStaleBackoff.Kind.values()).extracting(PurgeStaleBackoff.Kind::taskName,
                PurgeStaleBackoff.Kind::action).containsExactly(
                org.assertj.core.groups.Tuple.tuple("identity.purge-login-backoff",
                        "identity.login_backoff.purged"),
                org.assertj.core.groups.Tuple.tuple("identity.purge-mfa-totp-backoff",
                        "identity.mfa_totp_backoff.purged"));
    }

    /** Scenarios AT18, AT20 and the border: one entry with the count, and the row at the cutoff stays. */
    @ParameterizedTest
    @EnumSource(PurgeStaleBackoff.Kind.class)
    void theRowAtTheExactCutoffStaysTheOneASecondBeforeGoesAndTheEntryHoldsOnlyTheCount(
            PurgeStaleBackoff.Kind kind) {
        Instant now = databaseNow();
        Instant cutoff = now.minus(WINDOW);
        seed(kind, institutionId.value(), 2, cutoff.minusSeconds(1));
        seed(kind, institutionId.value(), 1, cutoff);
        seed(kind, institutionId.value(), 1, cutoff.plusSeconds(1));
        seed(kind, otherInstitution.value(), 2, cutoff.minus(Duration.ofDays(3)));
        RecurringRun run = runOf(kind.taskName());

        purge(kind, now).runFor(institutionId, run);

        assertThat(rows(kind, institutionId.value())).as("the border and the second after it").isEqualTo(2);
        assertThat(rows(kind, otherInstitution.value())).as("another institution is untouched").isEqualTo(2);
        assertThat(entries(institutionId, kind.action())).singleElement().satisfies(entry -> {
            assertThat(entry.actorKind()).isEqualTo("system");
            assertThat(entry.actorLabel()).isEqualTo("retention-purge");
            assertThat(entry.entityType()).isEqualTo("shared.job_run");
            assertThat(entry.entityId()).isEqualTo(run.runId().toString());
            assertThat(entry.requestId()).isEqualTo(run.runId());
            assertThat(detail(entry)).isEqualTo(detail("{\"purged\":2,\"cutoff\":\"" + cutoff + "\",\"batch\":1}"));
        });
        assertThat(entries(otherInstitution, kind.action())).isEmpty();
    }

    /** Scenarios I263 and AT19: the second run purges nothing, changes nothing, does not fail, and leaves its entry. */
    @ParameterizedTest
    @EnumSource(PurgeStaleBackoff.Kind.class)
    void aSecondRunReportsZeroChangesNothingAndStillLeavesItsEntry(PurgeStaleBackoff.Kind kind) {
        Instant now = databaseNow();
        seed(kind, institutionId.value(), 3, now.minus(WINDOW).minusSeconds(1));
        seed(kind, institutionId.value(), 2, now.minus(Duration.ofHours(1)));
        PurgeStaleBackoff purge = purge(kind, now);

        purge.runFor(institutionId, runOf(kind.taskName()));
        purge.runFor(institutionId, runOf(kind.taskName()));

        assertThat(rows(kind, institutionId.value())).isEqualTo(2);
        assertThat(entries(institutionId, kind.action())).extracting(IdentityPurgeFixtures::detail)
                .extracting(value -> value.get("purged").asLong()).containsExactly(3L, 0L);
    }

    /** Scenarios I274 and AT27: 12 000 stale login rows are three batches with their entries. */
    @Test
    void twelveThousandStaleLoginRowsAreThreeBatchesEachWithItsEntry() {
        Instant now = databaseNow();
        seed(PurgeStaleBackoff.Kind.LOGIN, institutionId.value(), 12_000, now.minus(WINDOW).minusSeconds(1));

        purge(PurgeStaleBackoff.Kind.LOGIN, now).runFor(institutionId, runOf("identity.purge-login-backoff"));

        assertThat(rows(PurgeStaleBackoff.Kind.LOGIN, institutionId.value())).isZero();
        assertThat(entries(institutionId, PurgeStaleBackoff.Kind.LOGIN.action()))
                .extracting(IdentityPurgeFixtures::detail)
                .extracting(value -> value.get("purged").asLong() + "@" + value.get("batch").asLong())
                .containsExactly("5000@1", "5000@2", "2000@3");
    }

    /** Scenario I276: each kind calls its own operation with the cutoff from the clock and the limit of 5 000. */
    @ParameterizedTest
    @EnumSource(PurgeStaleBackoff.Kind.class)
    void eachKindCallsItsOwnOperationWithTheCutoffComputedFromTheClockAndTheLimit(PurgeStaleBackoff.Kind kind) {
        RecordingPurges purges = new RecordingPurges(call -> 0);

        purge(kind, purges, Instant.parse("2026-10-25T10:45:01Z")).runFor(institutionId, runOf(kind.taskName()));

        assertThat(purges.calls).singleElement().satisfies(call -> {
            assertThat(call.operation()).isEqualTo(kind == PurgeStaleBackoff.Kind.LOGIN ? "loginBackoff"
                    : "mfaTotpBackoff");
            assertThat(call.cutoff()).isEqualTo(Instant.parse("2026-10-24T10:45:01Z"));
            assertThat(call.limit()).isEqualTo(5_000);
        });
    }
}
