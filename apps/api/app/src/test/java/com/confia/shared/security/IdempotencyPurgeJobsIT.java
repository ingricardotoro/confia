package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.infrastructure.JooqIdempotencyRecordStore;
import com.confia.shared.jobs.PurgeProbeRows;
import com.confia.shared.jobs.RecurringRun;
import com.confia.shared.observability.events.OperationalEventLog;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The daily purge of expired idempotency keys against a real PostgreSQL (scenarios I263 and I274 of
 * identity, AT18 to AT21 and AT27 of audit-trail, design.md decision 17), with the real adapter over
 * the {@code SECURITY DEFINER} function: batches until the purgeable rows are gone, one entry for every
 * batch with its count, the border of the cutoff exact to the microsecond (the clock of the use case is
 * fixed at {@code now()} of PostgreSQL, read once), another institution untouched, a second run that
 * changes nothing, and a batch that is lost together with its entry when the entry fails.
 */
class IdempotencyPurgeJobsIT extends CommittingPostgresIntegrationTest {

    private static final String TABLE = "shared_idempotency_key";
    private static final String ACTION = PurgeExpiredIdempotencyKeys.ACTION;
    private static final Duration WINDOW = Duration.ofHours(24);

    private final InstitutionId institution = new InstitutionId(UUID.randomUUID());
    private final InstitutionId other = new InstitutionId(UUID.randomUUID());

    private PurgeExpiredIdempotencyKeys purge(Instant now, AuditLogWriter audit) {
        return new PurgeExpiredIdempotencyKeys(transactionRunner(), new JooqIdempotencyRecordStore(dsl), audit,
                new OperationalEventLog(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static RecurringRun run() {
        return new RecurringRun(PurgeExpiredIdempotencyKeys.TASK_NAME, UUID.randomUUID(),
                Instant.parse("2026-10-25T10:40:00Z"));
    }

    private static Instant databaseNow() {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("select now()");
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getObject(1, OffsetDateTime.class).toInstant();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private long count(InstitutionId of) {
        return PurgeProbeRows.count(TABLE, of.value());
    }

    private List<String> entries(InstitutionId of) {
        return PurgeProbeRows.auditDetails(of.value(), ACTION);
    }

    /** Scenarios I274 and AT27: three batches of 5 000, 5 000 and 2 000 leave the live keys and one entry each. */
    @Test
    void batchesRunUntilNothingPurgeableIsLeftAndEachLeavesItsEntryWithItsCount() {
        Instant now = databaseNow();
        PurgeProbeRows.idempotencyKeys(institution.value(), 12_000, now.minus(WINDOW).minusSeconds(1));
        PurgeProbeRows.idempotencyKeys(institution.value(), 2, now.plus(Duration.ofHours(1)));
        RecurringRun run = run();

        purge(now, new JooqAuditLogWriter(dsl)).runFor(institution, run);

        assertThat(count(institution)).as("only the live keys are left").isEqualTo(2);
        String cutoff = now.minus(WINDOW).toString();
        assertThat(entries(institution)).hasSize(3).allSatisfy(detail -> assertThat(detail).contains(cutoff));
        assertThat(entries(institution).get(0)).contains("\"purged\": 5000").contains("\"batch\": 1");
        assertThat(entries(institution).get(2)).contains("\"purged\": 2000").contains("\"batch\": 3");
    }

    /** The border: the key that expired exactly at the cutoff stays, the one a second before goes. */
    @Test
    void theKeyAtTheExactCutoffStaysAndTheOneASecondBeforeItIsPurgedWithoutCrossingInstitutions() {
        Instant now = databaseNow();
        Instant cutoff = now.minus(WINDOW);
        PurgeProbeRows.idempotencyKeys(institution.value(), 1, cutoff.minusSeconds(1));
        PurgeProbeRows.idempotencyKeys(institution.value(), 1, cutoff);
        PurgeProbeRows.idempotencyKeys(institution.value(), 1, cutoff.plusSeconds(1));
        PurgeProbeRows.idempotencyKeys(other.value(), 2, cutoff.minus(Duration.ofDays(3)));

        purge(now, new JooqAuditLogWriter(dsl)).runFor(institution, run());

        assertThat(count(institution)).isEqualTo(2);
        assertThat(count(other)).as("another institution is untouched").isEqualTo(2);
        assertThat(entries(other)).isEmpty();
    }

    /** Scenarios AT19 and I263: nothing to purge still leaves an entry, and a second run changes nothing. */
    @Test
    void aSecondRunReportsZeroChangesNothingAndStillLeavesItsEntry() {
        Instant now = databaseNow();
        PurgeProbeRows.idempotencyKeys(institution.value(), 3, now.minus(WINDOW).minusSeconds(1));
        PurgeProbeRows.idempotencyKeys(institution.value(), 2, now.plus(Duration.ofHours(1)));
        PurgeExpiredIdempotencyKeys purge = purge(now, new JooqAuditLogWriter(dsl));

        purge.runFor(institution, run());
        purge.runFor(institution, run());

        assertThat(count(institution)).isEqualTo(2);
        assertThat(entries(institution)).hasSize(2);
        assertThat(entries(institution).get(1)).contains("\"purged\": 0");
    }

    /** Scenario AT21: an entry that fails after the function deleted the rows takes the purge with it. */
    @Test
    void anEntryThatFailsAfterTheDeletionLeavesTheKeysAndNoEntry() {
        Instant now = databaseNow();
        PurgeProbeRows.idempotencyKeys(institution.value(), 3, now.minus(WINDOW).minusSeconds(1));
        AuditLogWriter writesThenFails = entry -> {
            new JooqAuditLogWriter(dsl).append(entry);
            throw new IllegalStateException("the transaction is lost after the entry");
        };

        assertThatThrownBy(() -> purge(now, writesThenFails).runFor(institution, run()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count(institution)).as("the keys are still there").isEqualTo(3);
        assertThat(entries(institution)).as("and so is no entry").isEmpty();
    }
}
