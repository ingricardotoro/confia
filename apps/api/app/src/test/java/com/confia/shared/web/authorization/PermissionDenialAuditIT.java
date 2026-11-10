package com.confia.shared.web.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import com.confia.kernel.InstitutionId;
import com.confia.kernel.Permission;
import com.confia.shared.audit.AuditChainVerification;
import com.confia.shared.audit.AuditChainVerifier;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.audit.CanonicalAuditRowSerializer;
import com.confia.shared.audit.DefaultAuditChainVerifier;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.platform.infrastructure.SharedPlatformConfiguration;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.ClientAddress;
import com.confia.shared.security.RequestOrigin;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.web.authorization.DenialWindows.Key;
import com.confia.shared.web.harness.MutableClock;
import com.confia.shared.web.harness.RecordingAuthorizationMetrics;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The audit of the denials by permission against a real PostgreSQL, with the real transaction runner
 * and the real writer chain, a manual clock and an institution of its own per test
 * (rbac-permission-matrix-and-audit-integration design.md D9, D10 and D19; scenarios AZ40 to AZ45,
 * AZ80, AT32 to AT37, and the check DC-2). Every test builds its own {@link DenialWindows} and its own
 * recorder, so no test sees the counters, the queue or the gate of another. The writer can be wrapped
 * to fail or to count, which is how the failures are produced: no mock of the database.
 */
@SpringBootTest(classes = PermissionDenialAuditIT.Harness.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PermissionDenialAuditIT extends CommittingPostgresIntegrationTest {

    private static final Instant START = Instant.parse("2026-11-09T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final int CAP = 3;

    @DynamicPropertySource
    static void masterKey(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        registry.add("confia.crypto.column-master-key",
                () -> Base64.getEncoder().encodeToString(key));
    }

    @Autowired
    private AuditLogWriter realWriter;


    private final MutableClock clock = new MutableClock(START);
    private final RecordingAuthorizationMetrics metrics = new RecordingAuthorizationMetrics();

    private DenialWindows windows() {
        return new DenialWindows(CAP, WINDOW, 10_000, 10_000, 500, metrics);
    }

    private PermissionDenialAudit audit(DenialWindows windows, AuditLogWriter writer) {
        return new PermissionDenialAudit(transactionRunner(), writer, windows, metrics, clock, WINDOW);
    }

    private static AuthenticatedActor actor(UUID institution, UUID account) {
        return new AuthenticatedActor(account, institution, UUID.randomUUID(), UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD), START,
                START.plus(Duration.ofMinutes(15)));
    }

    private static PermissionDeniedException denied(UUID institution, UUID account,
            Permission permission, String template) {
        return new PermissionDeniedException(permission, template, actor(institution, account));
    }

    private List<Record> rows(UUID institution) {
        return transactionRunner().execute(new SecurityContext("", "system", institution.toString(),
                UUID.randomUUID().toString()), () -> new ArrayList<>(dsl.fetch("""
                        select action, outcome, actor_kind, actor_id, actor_label, entity_type, entity_id,
                               request_id, trace_id, before_value::text as before_value,
                               after_value::text as after_value, reason, approver_id,
                               host(source_ip) as ip, user_agent
                          from shared_audit_log where institution_id = ? order by id
                        """, institution)));
    }

    private static List<Record> withAction(List<Record> rows, String action) {
        return rows.stream().filter(row -> action.equals(row.get("action", String.class))).toList();
    }

    private void assertChainIntact(UUID institution, long rows) {
        AuditChainVerifier verifier = new DefaultAuditChainVerifier(transactionRunner(),
                new JooqAuditLogReader(dsl), new CanonicalAuditRowSerializer());
        assertThat(verifier.verifyChainOf(new InstitutionId(institution)))
                .asInstanceOf(type(AuditChainVerification.Intact.class))
                .satisfies(intact -> assertThat(intact.verifiedRows()).isEqualTo(rows));
    }

    /** Five denials of one key with a cap of three: three full rows and two omitted. */
    private void fiveDenials(PermissionDenialAudit audit, UUID institution, UUID account) {
        for (int i = 0; i < 5; i++) {
            audit.record(denied(institution, account, Permission.IDENTITY_READ,
                    "/api/v1/identity/roles"), UUID.randomUUID().toString());
        }
    }

    // --- AZ40 to AZ44: the full row ---

    @Test
    void oneDenialWritesOneRowWithTheExactShapeAndTheTypedDetail() {
        UUID institution = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        String requestId = UUID.randomUUID().toString();

        audit(windows(), realWriter).record(denied(institution, account, Permission.IDENTITY_READ,
                "/api/v1/identity/roles"), requestId);

        List<Record> rows = rows(institution);
        assertThat(rows).hasSize(1);
        Record row = rows.get(0);
        assertThat(row.get("action", String.class)).isEqualTo("authorization.access.denied");
        assertThat(row.get("outcome", String.class)).isEqualTo("denied");
        assertThat(row.get("actor_kind", String.class)).isEqualTo("staff");
        assertThat(row.get("actor_id", UUID.class)).isEqualTo(account);
        assertThat(row.get("actor_label", String.class)).as("the identifier, never the email")
                .isEqualTo(account.toString());
        assertThat(row.get("entity_type", String.class)).isEqualTo("authorization.permission");
        assertThat(row.get("entity_id", String.class)).isEqualTo("identity:read");
        assertThat(row.get("request_id", UUID.class)).as("the trace id of the problem")
                .isEqualTo(UUID.fromString(requestId));
        assertThat(row.get("after_value", String.class))
                .isEqualTo("{\"routeTemplate\": \"/api/v1/identity/roles\"}");
        assertThat(row.get("before_value")).isNull();
        assertThat(row.get("reason")).isNull();
        assertThat(row.get("approver_id")).isNull();
        assertThat(row.toString()).doesNotContain("@");
        assertChainIntact(institution, 1);
    }

    @Test
    void theRowIsCommittedBeforeRecordReturnsAndNoTransactionIsOpenOnEntry() {
        UUID institution = UUID.randomUUID();
        AtomicInteger seenInside = new AtomicInteger();
        AuditLogWriter probing = entry -> {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                seenInside.incrementAndGet();
            }
            realWriter.append(entry);
        };

        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("on entry to the recorder").isFalse();
        audit(windows(), probing).record(denied(institution, UUID.randomUUID(),
                Permission.IDENTITY_READ, "/x"), UUID.randomUUID().toString());

        assertThat(seenInside.get()).as("the write ran in a transaction of its own").isEqualTo(1);
        assertThat(rows(institution)).as("visible from another transaction: committed").hasSize(1);
    }

    @Test
    void theTemplateOfTheEntryIsRecordedAndNeverTheValueOfTheRequest() {
        UUID institution = UUID.randomUUID();

        audit(windows(), realWriter).record(denied(institution, UUID.randomUUID(),
                Permission.IDENTITY_READ, "/test/accounts/{id}"), UUID.randomUUID().toString());

        Record row = rows(institution).get(0);
        assertThat(row.get("after_value", String.class))
                .isEqualTo("{\"routeTemplate\": \"/test/accounts/{id}\"}")
                .doesNotContain("SENTINEL-ID-9");
    }

    // --- AZ45: the cap decides outside the transaction ---

    @Test
    void aRetryOfTheRunnerDoesNotCountTheDenialTwice() {
        UUID institution = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        AuditLogWriter failingOnce = entry -> {
            if (attempts.incrementAndGet() == 1) {
                throw new CannotAcquireLockException("a serialization failure, retried");
            }
            realWriter.append(entry);
        };
        PermissionDenialAudit audit = audit(windows(), failingOnce);

        for (int i = 0; i < 4; i++) {
            audit.record(denied(institution, account, Permission.IDENTITY_READ, "/x"),
                    UUID.randomUUID().toString());
        }

        assertThat(attempts.get()).as("two attempts for the first row, one for each of two more")
                .isEqualTo(4);
        assertThat(rows(institution)).as("three full rows").hasSize(CAP);
        assertThat(metrics.denialsOmitted()).as("exactly one omitted, not two").hasSize(1);
        assertChainIntact(institution, CAP);
    }

    @Test
    void twoInstitutionsKeepTwoIntactChains() {
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        PermissionDenialAudit audit = audit(windows(), realWriter);

        for (int i = 0; i < 2; i++) {
            audit.record(denied(one, UUID.randomUUID(), Permission.IDENTITY_READ, "/x"),
                    UUID.randomUUID().toString());
            audit.record(denied(two, UUID.randomUUID(), Permission.IDENTITY_READ, "/x"),
                    UUID.randomUUID().toString());
        }

        assertThat(rows(one)).hasSize(2);
        assertThat(rows(two)).hasSize(2);
        assertChainIntact(one, 2);
        assertChainIntact(two, 2);
    }

    // --- AT35, AT36: the summary ---

    @Test
    void theSummaryCarriesTheCountTheWindowTheAccountAndThePermissionAndNoOrigin() {
        UUID institution = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        PermissionDenialAudit audit = audit(windows(), realWriter);
        fiveDenials(audit, institution, account);
        assertThat(withAction(rows(institution), "authorization.access.denied_summary")).isEmpty();
        clock.advance(WINDOW.plusSeconds(1));

        // Another key fires the emission, from a request with an address and an agent of its own.
        UUID other = UUID.randomUUID();
        ScopedValue.where(RequestOrigin.CURRENT, new RequestOrigin(UUID.randomUUID(),
                ClientAddress.parseLiteral("203.0.113.77"), "agente-ajeno/9")).run(() ->
                audit.record(denied(institution, other, Permission.IDENTITY_READ, "/x"),
                        UUID.randomUUID().toString()));

        List<Record> rows = rows(institution);
        List<Record> summaries = withAction(rows, "authorization.access.denied_summary");
        assertThat(summaries).hasSize(1);
        Record summary = summaries.get(0);
        assertThat(summary.get("outcome", String.class)).isEqualTo("denied");
        assertThat(summary.get("actor_id", UUID.class)).isEqualTo(account);
        assertThat(summary.get("entity_id", String.class)).isEqualTo("identity:read");
        // jsonb keeps the members, not their order: it sorts them by length and then by name.
        assertThat(summary.get("after_value", String.class)).contains("\"omittedCount\": 2",
                "\"windowStart\": \"2026-11-09T10:00:00Z\"", "\"windowSeconds\": 300");
        assertThat(summary.get("ip")).as("not the address of the request that fired it").isNull();
        assertThat(summary.get("user_agent")).as("nor its agent").isNull();
        assertThat(summary.toString()).doesNotContain("203.0.113.77", "agente-ajeno", "/x");
        Record trigger = withAction(rows, "authorization.access.denied").stream()
                .filter(row -> other.equals(row.get("actor_id", UUID.class))).findFirst()
                .orElseThrow();
        assertThat(trigger.get("ip", String.class)).as("the origin was bound: the control")
                .isEqualTo("203.0.113.77");
        assertThat(withAction(rows, "authorization.access.denied")).as("3 full and the trigger")
                .hasSize(CAP + 1);
        assertChainIntact(institution, CAP + 2);
    }

    // --- AZ80: a full row that fails is not lost ---

    @Test
    void failedFullRowIsCountedInTheSummary() {
        UUID institution = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        AtomicInteger inserts = new AtomicInteger();
        AuditLogWriter failingFirst = entry -> {
            if (inserts.incrementAndGet() == 1) {
                throw new IllegalStateException("the first insert fails SENTINEL-WRITER");
            }
            realWriter.append(entry);
        };
        PermissionDenialAudit audit = audit(windows(), failingFirst);

        fiveDenials(audit, institution, account);
        clock.advance(WINDOW.plusSeconds(1));
        audit.record(denied(institution, UUID.randomUUID(), Permission.IDENTITY_READ, "/x"),
                UUID.randomUUID().toString());

        assertThat(metrics.denialAuditFailedCount()).isEqualTo(1);
        List<Record> rows = rows(institution);
        assertThat(withAction(rows, "authorization.access.denied").stream()
                .filter(row -> account.equals(row.get("actor_id", UUID.class)))).hasSize(CAP);
        Record summary = withAction(rows, "authorization.access.denied_summary").get(0);
        assertThat(summary.get("after_value", String.class)).contains("\"omittedCount\": 2");
    }

    @Test
    void aBrokenWriterNeverMakesRecordThrowAndCountsOnce() {
        AuditLogWriter broken = entry -> {
            throw new IllegalStateException("boom SENTINEL-WRITER");
        };

        audit(windows(), broken).record(denied(UUID.randomUUID(), UUID.randomUUID(),
                Permission.IDENTITY_READ, "/x"), UUID.randomUUID().toString());

        assertThat(metrics.denialAuditFailedCount()).isEqualTo(1);
    }

    // --- The pending queue: bounded work for one denial ---

    @Test
    void oneDenialWritesAtMostOneSummary() {
        UUID institution = UUID.randomUUID();
        DenialWindows windows = windows();
        for (int i = 0; i < 5; i++) {
            windows.untrackedRowFailed(new Key(institution, UUID.randomUUID(),
                    Permission.IDENTITY_READ), clock.instant());
        }
        AtomicInteger inserts = new AtomicInteger();
        AuditLogWriter counting = entry -> {
            inserts.incrementAndGet();
            realWriter.append(entry);
        };

        audit(windows, counting).record(denied(institution, UUID.randomUUID(),
                Permission.IDENTITY_READ, "/x"), UUID.randomUUID().toString());

        assertThat(inserts.get()).as("its own row and one summary").isEqualTo(2);
        assertThat(windows.pendingSize()).isEqualTo(4);
    }

    @Test
    void failedSummaryReturnsToTheHeadAndStopsThePass() {
        UUID institution = UUID.randomUUID();
        DenialWindows windows = windows();
        Key head = new Key(institution, UUID.randomUUID(), Permission.IDENTITY_READ);
        windows.untrackedRowFailed(head, clock.instant());
        windows.untrackedRowFailed(new Key(institution, UUID.randomUUID(), Permission.IDENTITY_READ),
                clock.instant());
        AtomicInteger summaryAttempts = new AtomicInteger();
        AuditLogWriter failingSummaries = entry -> {
            if (entry.action().endsWith("_summary")) {
                summaryAttempts.incrementAndGet();
                throw new IllegalStateException("a summary fails SENTINEL-WRITER");
            }
            realWriter.append(entry);
        };

        audit(windows, failingSummaries).record(denied(institution, UUID.randomUUID(),
                Permission.IDENTITY_READ, "/x"), UUID.randomUUID().toString());

        assertThat(summaryAttempts.get()).as("no second attempt in the same pass").isEqualTo(1);
        assertThat(metrics.denialAuditFailedCount()).isEqualTo(1);
        assertThat(windows.pendingSize()).isEqualTo(2);
        assertThat(windows.pollPending().orElseThrow().key()).as("back at the head").isEqualTo(head);
    }

    @Test
    void requeueIntoAFullQueueDiscardsAndCounts() {
        UUID institution = UUID.randomUUID();
        DenialWindows windows = new DenialWindows(CAP, WINDOW, 10_000, 1, 500, metrics);
        windows.untrackedRowFailed(new Key(institution, UUID.randomUUID(), Permission.IDENTITY_READ),
                clock.instant());
        AuditLogWriter fillsTheQueueThenFails = entry -> {
            if (entry.action().endsWith("_summary")) {
                windows.untrackedRowFailed(new Key(institution, UUID.randomUUID(),
                        Permission.IDENTITY_READ), clock.instant());
                throw new IllegalStateException("a summary fails SENTINEL-WRITER");
            }
            realWriter.append(entry);
        };

        audit(windows, fillsTheQueueThenFails).record(denied(institution, UUID.randomUUID(),
                Permission.IDENTITY_READ, "/x"), UUID.randomUUID().toString());

        assertThat(metrics.denialAuditFailedCount()).as("the failed write and the discard")
                .isEqualTo(2);
        assertThat(windows.pendingSize()).isEqualTo(1);
    }

    // --- DC-2: the shutdown ---

    @Test
    void shutdownWritesPendingSummariesAndNothingWhenThereAreNoOmissions() {
        UUID institution = UUID.randomUUID();
        DenialWindows windows = windows();
        PermissionDenialAudit audit = audit(windows, realWriter);
        audit.record(denied(institution, UUID.randomUUID(), Permission.IDENTITY_READ, "/x"),
                UUID.randomUUID().toString());
        audit.destroy();
        assertThat(withAction(rows(institution), "authorization.access.denied_summary")).isEmpty();

        UUID noisy = UUID.randomUUID();
        fiveDenials(audit, institution, noisy);
        audit.destroy();

        List<Record> summaries = withAction(rows(institution), "authorization.access.denied_summary");
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).get("after_value", String.class)).contains("\"omittedCount\": 2");
    }

    @Test
    void shutdownStopsAtTheFirstFailure() {
        UUID institution = UUID.randomUUID();
        DenialWindows windows = windows();
        for (int i = 0; i < 3; i++) {
            windows.untrackedRowFailed(new Key(institution, UUID.randomUUID(),
                    Permission.IDENTITY_READ), clock.instant());
        }
        AtomicInteger attempts = new AtomicInteger();
        AuditLogWriter failing = entry -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("the database is down SENTINEL-WRITER");
        };

        audit(windows, failing).destroy();

        assertThat(attempts.get()).as("one attempt").isEqualTo(1);
        assertThat(metrics.denialAuditFailedCount()).as("one per summary not written").isEqualTo(3);
    }

    @Test
    void shutdownStopsWhenItsBudgetIsSpent() {
        UUID institution = UUID.randomUUID();
        DenialWindows windows = windows();
        for (int i = 0; i < 5; i++) {
            windows.untrackedRowFailed(new Key(institution, UUID.randomUUID(),
                    Permission.IDENTITY_READ), clock.instant());
        }
        AuditLogWriter slow = entry -> {
            realWriter.append(entry);
            clock.advance(Duration.ofSeconds(4));
        };

        audit(windows, slow).destroy();

        assertThat(withAction(rows(institution), "authorization.access.denied_summary"))
                .as("three fit in the ten seconds").hasSize(3);
        assertThat(metrics.denialAuditFailedCount()).as("the rest count as not written")
                .isEqualTo(2);
    }

    /** Only the platform: the real writer chain, the real runner and the data source. */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
    @Import(SharedPlatformConfiguration.class)
    static class Harness {
    }
}
