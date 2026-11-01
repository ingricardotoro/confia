package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.jobs.PurgeProbeRows;
import com.confia.shared.jobs.RecurringRun;
import com.confia.shared.observability.events.OperationalEventLog;
import com.confia.support.AuditChainRows;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The daily and weekly cadences of the chain verifier against a real PostgreSQL
 * (background-jobs-with-db-scheduler design.md decision 19; scenarios AT07 to AT09, AT11 to AT14, AT23, AT26,
 * AT30 and AT31). Every row is inserted at an instant the test chooses and every alteration is a real
 * {@code SUPERUSER} write that recalculates no hash. The clock of the jobs is fixed at 2026-10-25 at 03:00 in
 * Tegucigalpa, so the window of the daily cadence starts on 2026-09-01 at 00:00 there (06:00 UTC).
 */
class AuditChainVerificationJobsIT extends CommittingPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-25T09:00:00Z");
    private static final Instant JULY = Instant.parse("2026-07-01T15:00:00Z");
    private static final Instant AUGUST = Instant.parse("2026-08-15T15:00:00Z");
    private static final Instant SEPTEMBER = Instant.parse("2026-09-05T15:00:00Z");
    private static final Instant LATE_SEPTEMBER = Instant.parse("2026-09-20T15:00:00Z");
    private static final Instant OCTOBER = Instant.parse("2026-10-10T15:00:00Z");
    private static final String VERIFIED = AuditChainVerificationJobs.VERIFIED_ACTION;
    private static final String FAILED = AuditChainVerificationJobs.FAILED_ACTION;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        ((Logger) LoggerFactory.getLogger(OperationalEventLog.class)).addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(OperationalEventLog.class)).detachAppender(logs);
    }

    private AuditChainVerificationJobs jobs() {
        return jobs(new JooqAuditLogReader(dsl), new JooqAuditLogWriter(dsl));
    }

    private AuditChainVerificationJobs jobs(AuditLogReader reader, AuditLogWriter writer) {
        return new AuditChainVerificationJobs(transactionRunner(),
                new DefaultAuditChainVerifier(transactionRunner(), reader, new CanonicalAuditRowSerializer()),
                writer, new OperationalEventLog(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static RecurringRun run(String task) {
        return new RecurringRun(task, UUID.randomUUID(), NOW);
    }

    private void daily(UUID institution) {
        jobs().daily().handler().runFor(new InstitutionId(institution), run(AuditChainVerificationJobs.DAILY_TASK));
    }

    private void weekly(UUID institution) {
        jobs().weekly().handler().runFor(new InstitutionId(institution), run(AuditChainVerificationJobs.WEEKLY_TASK));
    }

    /** Two rows before the window and three inside it: 5 in the whole chain, 3 in the window. */
    private static long[] seedChain(UUID institution) {
        return new long[] {AuditChainRows.insert(institution, JULY), AuditChainRows.insert(institution, AUGUST),
                AuditChainRows.insert(institution, SEPTEMBER), AuditChainRows.insert(institution, LATE_SEPTEMBER),
                AuditChainRows.insert(institution, OCTOBER)};
    }

    private static long rows(UUID institution) {
        return PurgeProbeRows.scalar("select count(*) from shared_audit_log where institution_id = ?", institution);
    }

    private static List<String> entries(UUID institution, String action) {
        return PurgeProbeRows.auditDetails(institution, action);
    }

    private List<String> events(Level level) {
        return logs.list.stream().filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Scenarios AT07 and AT13: an intact chain is reported by both cadences with the rows each one verified. */
    @Test
    void anIntactChainIsRecordedByBothCadencesWithTheRowsEachVerified() {
        UUID dailyInstitution = UUID.randomUUID();
        UUID weeklyInstitution = UUID.randomUUID();
        seedChain(dailyInstitution);
        seedChain(weeklyInstitution);

        daily(dailyInstitution);
        weekly(weeklyInstitution);

        assertThat(entries(dailyInstitution, VERIFIED)).containsExactly("{\"scope\": \"daily\", \"verifiedRows\": 3}");
        assertThat(entries(weeklyInstitution, VERIFIED))
                .containsExactly("{\"scope\": \"weekly\", \"verifiedRows\": 5}");
        assertThat(entries(dailyInstitution, FAILED)).isEmpty();
        assertThat(events(Level.INFO)).containsExactly("audit_chain_verified", "audit_chain_verified");
        assertThat(events(Level.ERROR)).as("no S1 alert").isEmpty();
    }

    /** Scenarios AT08, AT12 and AT14: an alteration inside the window is named, nothing is repaired, and another institution is unaffected. */
    @Test
    void aRowAlteredInsideTheWindowIsReportedByTheDailyCadenceAndNothingIsRepaired() {
        UUID altered = UUID.randomUUID();
        UUID clean = UUID.randomUUID();
        long target = seedChain(altered)[3];
        seedChain(clean);
        AuditChainRows.alter(altered, target);

        assertThatThrownBy(() -> daily(altered)).isInstanceOfSatisfying(AuditChainBrokenException.class,
                thrown -> assertThat(thrown.firstDivergentId()).isEqualTo(target));
        daily(clean);

        assertThat(entries(altered, FAILED)).singleElement().asString()
                .contains("\"scope\": \"daily\"", "\"verifiedRows\": 1", "\"firstDivergentId\": " + target,
                        "\"divergence\": \"ROW_HASH_MISMATCH\"");
        assertThat(PurgeProbeRows.scalar("select count(*) from shared_audit_log where institution_id = ? "
                + "and action = ? and outcome = 'error'", altered, FAILED)).isEqualTo(1);
        assertThat(entries(altered, VERIFIED)).isEmpty();
        assertThat(rows(altered)).as("the five rows and the entry of the result, nothing else").isEqualTo(6);
        assertThat(PurgeProbeRows.scalar("select count(*) from shared_audit_log where institution_id = ? "
                + "and id = ? and reason = 'altered by the test'", altered, target))
                .as("the altered row is kept as evidence").isEqualTo(1);
        assertThat(entries(clean, VERIFIED)).containsExactly("{\"scope\": \"daily\", \"verifiedRows\": 3}");
        assertThat(entries(clean, FAILED)).as("the alteration of the other institution is not seen here").isEmpty();
        assertThat(events(Level.ERROR)).containsExactly("audit_chain_broken");
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.ERROR).singleElement()
                .satisfies(event -> assertThat(event.getKeyValuePairs())
                        .extracting(pair -> pair.key + "=" + pair.value)
                        .contains("institution=" + altered, "scope=daily", "firstDivergentId=" + target));
    }

    /** Scenarios AT09 and AT11: an alteration older than the window is seen by the weekly cadence only. */
    @Test
    void anAlterationOlderThanTheWindowIsReportedOnlyByTheWeeklyCadence() {
        UUID institution = UUID.randomUUID();
        long target = seedChain(institution)[0];
        AuditChainRows.alter(institution, target);

        daily(institution);
        assertThat(entries(institution, VERIFIED)).containsExactly("{\"scope\": \"daily\", \"verifiedRows\": 3}");
        assertThat(entries(institution, FAILED)).isEmpty();

        assertThatThrownBy(() -> weekly(institution)).isInstanceOfSatisfying(AuditChainBrokenException.class,
                thrown -> assertThat(thrown.firstDivergentId()).isEqualTo(target));
        assertThat(entries(institution, FAILED)).singleElement().asString()
                .contains("\"scope\": \"weekly\"", "\"verifiedRows\": 0", "\"firstDivergentId\": " + target);
    }

    /** Scenario AT23: the window starts at 00:00 on 1 September in Tegucigalpa, to the second. */
    @Test
    void theWindowStartsAtMidnightOfTheFirstOfThePreviousMonthInTegucigalpa() {
        UUID counted = UUID.randomUUID();
        UUID outsideAltered = UUID.randomUUID();
        UUID insideAltered = UUID.randomUUID();
        long lastSecondOfAugust = 0;
        long tenthOfSeptember = 0;
        for (UUID institution : List.of(counted, outsideAltered, insideAltered)) {
            long august = AuditChainRows.insert(institution, Instant.parse("2026-09-01T05:59:59Z"));
            AuditChainRows.insert(institution, Instant.parse("2026-09-01T06:00:00Z"));
            long september = AuditChainRows.insert(institution, Instant.parse("2026-09-10T15:00:00Z"));
            if (institution.equals(outsideAltered)) {
                lastSecondOfAugust = august;
            }
            if (institution.equals(insideAltered)) {
                tenthOfSeptember = september;
            }
        }
        AuditChainRows.alter(outsideAltered, lastSecondOfAugust);
        AuditChainRows.alter(insideAltered, tenthOfSeptember);

        daily(counted);
        daily(outsideAltered);

        assertThat(entries(counted, VERIFIED)).as("the row of 31 August 23:59:59 is outside")
                .containsExactly("{\"scope\": \"daily\", \"verifiedRows\": 2}");
        assertThat(entries(outsideAltered, VERIFIED)).as("so an alteration of it is not seen")
                .containsExactly("{\"scope\": \"daily\", \"verifiedRows\": 2}");
        long expected = tenthOfSeptember;
        assertThatThrownBy(() -> daily(insideAltered)).isInstanceOfSatisfying(AuditChainBrokenException.class,
                thrown -> assertThat(thrown.firstDivergentId())
                        .as("an alteration 45 days old, outside a rolling month").isEqualTo(expected));
    }

    /** Scenarios AT26 and AT30: nothing to verify leaves the entry with zero rows and a warning, and no alert. */
    @Test
    void aConfiguredInstitutionWithoutRowsInTheScopeLeavesAZeroEntryAndAWarning() {
        UUID noRows = UUID.randomUUID();
        UUID onlyOldRows = UUID.randomUUID();
        AuditChainRows.insert(onlyOldRows, JULY);

        daily(noRows);
        weekly(UUID.randomUUID());
        daily(onlyOldRows);

        assertThat(entries(noRows, VERIFIED)).containsExactly("{\"scope\": \"daily\", \"verifiedRows\": 0}");
        assertThat(entries(onlyOldRows, VERIFIED)).containsExactly("{\"scope\": \"daily\", \"verifiedRows\": 0}");
        assertThat(events(Level.WARN)).containsExactly("audit_chain_empty_for_configured_institution",
                "audit_chain_empty_for_configured_institution", "audit_chain_empty_for_configured_institution");
        assertThat(events(Level.ERROR)).isEmpty();
    }

    /** Scenario AT31: the walk commits first; the entry of the result is written afterwards, in another transaction. */
    @Test
    void theEntryIsWrittenAfterTheWalkInATransactionOfItsOwn() {
        UUID institution = UUID.randomUUID();
        seedChain(institution);
        List<String> seen = new ArrayList<>();
        AuditLogReader spyReader = new AuditLogReader() {
            private final AuditLogReader real = new JooqAuditLogReader(dsl);

            @Override
            public List<AuditRowSnapshot> pageOf(InstitutionId institutionId, long afterId, int pageSize) {
                seen.add("walk:" + dsl.fetchValue("select txid_current()"));
                return real.pageOf(institutionId, afterId, pageSize);
            }

            @Override
            public Optional<AuditRowSnapshot> firstAtOrAfter(InstitutionId institutionId, Instant occurredAt) {
                seen.add("walk:" + dsl.fetchValue("select txid_current()"));
                return real.firstAtOrAfter(institutionId, occurredAt);
            }
        };
        AuditLogWriter spyWriter = entry -> {
            seen.add("entry:" + dsl.fetchValue("select txid_current()"));
            new JooqAuditLogWriter(dsl).append(entry);
        };

        jobs(spyReader, spyWriter).daily().handler().runFor(new InstitutionId(institution),
                run(AuditChainVerificationJobs.DAILY_TASK));

        assertThat(seen).hasSizeGreaterThanOrEqualTo(2);
        assertThat(seen.subList(0, seen.size() - 1)).allMatch(line -> line.startsWith("walk:"));
        String lastLine = seen.get(seen.size() - 1);
        assertThat(lastLine).startsWith("entry:");
        assertThat(seen.stream().filter(line -> line.startsWith("walk:")).map(line -> line.substring(5)).distinct())
                .singleElement().isNotEqualTo(lastLine.substring(6));
    }
}
