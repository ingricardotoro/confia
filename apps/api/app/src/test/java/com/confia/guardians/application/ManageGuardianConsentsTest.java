package com.confia.guardians.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.guardians.domain.ConsentChannelUnavailableException;
import com.confia.guardians.domain.ConsentEvent;
import com.confia.guardians.domain.Guardian;
import com.confia.guardians.domain.GuardianNotActiveException;
import com.confia.guardians.domain.GuardianStatus;
import com.confia.guardians.domain.PreferredChannel;
import com.confia.guardians.domain.ResourceNotFoundException;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.TransactionRunner;
import com.confia.support.JdbcStubTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rules and edge cases of docs/cortes/guardians-consent.md through the use case, with the ports in memory and a
 * transaction stub that tells a commit from a rollback. The races (two simultaneous changes of the same guardian) are
 * settled by the lock on the row of the guardian and proven against PostgreSQL in {@code GuardianConsentEndpointsIT};
 * here the order in which the use case locks and writes, the idempotency, the audit entry and the isolation are proven.
 */
class ManageGuardianConsentsTest {

    private static final Instant NOW = Instant.parse("2026-03-10T15:00:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-03-10T15:00:00.123456Z");
    private static final String PAPER = "SIGNED_PAPER_FORM";
    private static final String ADMIN = "ADMIN_REGISTERED";

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<String> readOnlyFlags = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final InMemoryGuardians world = new InMemoryGuardians();
    private final ConsentStore store = new ConsentStore();
    private final TransactionRunner runner = JdbcStubTransactions.runner(transactionCalls, readOnlyFlags);
    private final AuditLogWriter auditWriter = audit::add;
    private final UUID institutionUuid = UUID.randomUUID();
    private final InstitutionId institution = new InstitutionId(institutionUuid);
    private final AuthenticatedActor actor = actorOf(institutionUuid);
    private final String requestId = UUID.randomUUID().toString();
    private final ManageGuardianConsents consents = new ManageGuardianConsents(runner, world.guardians, store,
            auditWriter, Clock.fixed(NOW, ZoneOffset.UTC));

    /** The consent events in memory: it appends and reads, as the real adapter does, and records its calls. */
    private final class ConsentStore implements GuardianConsentRepository {

        final List<StoredEvent> rows = new ArrayList<>();

        record StoredEvent(UUID institution, ConsentEvent event) {
        }

        @Override
        public List<ConsentEvent> latestOfGuardian(InstitutionId inst, UUID guardianId) {
            world.events.add("read-consents");
            List<ConsentEvent> all = eventsOf(inst, guardianId);
            return all.stream().filter(event -> all.stream().filter(other -> other.channel() == event.channel()
                    && other.purpose() == event.purpose()).reduce((first, second) -> second).orElseThrow() == event)
                    .toList();
        }

        @Override
        public List<ConsentEvent> recentOfGuardian(InstitutionId inst, UUID guardianId, int limit) {
            List<ConsentEvent> newestFirst = new ArrayList<>(eventsOf(inst, guardianId));
            java.util.Collections.reverse(newestFirst);
            return newestFirst.stream().limit(limit).toList();
        }

        @Override
        public void append(InstitutionId inst, ConsentEvent event) {
            world.events.add("append-consent");
            rows.add(new StoredEvent(inst.value(), event));
        }

        private List<ConsentEvent> eventsOf(InstitutionId inst, UUID guardianId) {
            return rows.stream().filter(row -> row.institution().equals(inst.value())
                    && row.event().guardianId().equals(guardianId)).map(StoredEvent::event).toList();
        }
    }

    private static AuthenticatedActor actorOf(UUID institutionId) {
        return new AuthenticatedActor(UUID.randomUUID(), institutionId, UUID.randomUUID(), UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    }

    private Guardian guardian(String email, String phone, GuardianStatus status) {
        Guardian guardian = new Guardian(UUID.randomUUID(), "P-000001", "Nombre-Reservado", "Apellido-Reservado", false,
                false, email, phone, "Direccion-Privada", PreferredChannel.NONE, status, NOW_MICROS, NOW_MICROS);
        world.arrange(institution, guardian);
        return guardian;
    }

    private Guardian guardian() {
        return guardian("reservado@example.org", "99998888", GuardianStatus.ACTIVE);
    }

    private ConsentChangeView grant(Guardian guardian, String channel, String purpose) {
        return consents.record(actor, requestId, guardian.id(), channel, purpose, "GRANT", PAPER, "2026-01");
    }

    private ConsentChangeView withdraw(Guardian guardian, String channel, String purpose) {
        return consents.record(actor, requestId, guardian.id(), channel, purpose, "WITHDRAW", ADMIN, null);
    }

    private boolean committed() {
        return transactionCalls.contains("commit") && !transactionCalls.contains("rollback");
    }

    private static JsonNode json(String document) {
        return JsonMapper.builder().build().readTree(document);
    }

    // --- The acceptance flow ---

    @Test
    void anAdministratorGrantsTheInstitutionalConsentByEmailWithPaperEvidenceThenWithdrawsItAndTheHistoryKeepsBoth() {
        Guardian guardian = guardian();

        GuardianConsentsView before = consents.of(actor, requestId, guardian.id());
        assertThat(before.states()).hasSize(6).extracting(GuardianConsentsView.Standing::state)
                .containsOnly("NEVER_GRANTED");
        assertThat(before.history()).isEmpty();

        ConsentChangeView granted = grant(guardian, "EMAIL", "INSTITUTIONAL");
        assertThat(granted.changed()).isTrue();
        assertThat(granted.standing().state()).isEqualTo("GRANTED");
        assertThat(granted.standing().policyVersion()).isEqualTo("2026-01");
        assertThat(granted.standing().evidenceSource()).isEqualTo(PAPER);
        assertThat(granted.standing().since()).as("server time, to the microsecond").isEqualTo(NOW_MICROS);

        ConsentChangeView withdrawn = withdraw(guardian, "EMAIL", "INSTITUTIONAL");
        assertThat(withdrawn.changed()).isTrue();
        assertThat(withdrawn.standing().state()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.standing().policyVersion()).isNull();

        GuardianConsentsView after = consents.of(actor, requestId, guardian.id());
        assertThat(after.history()).extracting(GuardianConsentsView.Event::action).containsExactly("WITHDRAW", "GRANT");
        assertThat(after.history().get(1).policyVersion()).isEqualTo("2026-01");
        assertThat(after.history().get(1).evidenceSource()).isEqualTo(PAPER);
        assertThat(after.history().get(0).evidenceSource()).isEqualTo(ADMIN);
        GuardianConsentsView.Standing email = after.states().get(0);
        assertThat(email.channel()).isEqualTo("EMAIL");
        assertThat(email.purpose()).isEqualTo("INSTITUTIONAL");
        assertThat(email.state()).isEqualTo("WITHDRAWN");
        assertThat(after.states().subList(1, 6)).extracting(GuardianConsentsView.Standing::state)
                .containsOnly("NEVER_GRANTED");
        assertThat(store.rows).hasSize(2);
        assertThat(store.rows).extracting(row -> row.event().recordedBy()).containsOnly(actor.accountId());
    }

    @Test
    void grantWithdrawAndGrantAgainIsAHistoryOfThreeEvents() {
        Guardian guardian = guardian();

        grant(guardian, "SMS", "MARKETING");
        withdraw(guardian, "SMS", "MARKETING");
        grant(guardian, "SMS", "MARKETING");

        GuardianConsentsView view = consents.of(actor, requestId, guardian.id());
        assertThat(view.history()).hasSize(3);
        assertThat(view.states().get(3).state()).isEqualTo("GRANTED");
        assertThat(view.states().get(3).channel()).isEqualTo("SMS");
        assertThat(view.states().get(3).purpose()).isEqualTo("MARKETING");
    }

    @Test
    void theSixCombinationsAreIndependent() {
        Guardian guardian = guardian();

        grant(guardian, "EMAIL", "INSTITUTIONAL");
        grant(guardian, "WHATSAPP", "MARKETING");
        withdraw(guardian, "EMAIL", "INSTITUTIONAL");

        GuardianConsentsView view = consents.of(actor, requestId, guardian.id());
        assertThat(view.states()).extracting(s -> s.channel() + ":" + s.purpose() + ":" + s.state()).containsExactly(
                "EMAIL:INSTITUTIONAL:WITHDRAWN", "EMAIL:MARKETING:NEVER_GRANTED", "SMS:INSTITUTIONAL:NEVER_GRANTED",
                "SMS:MARKETING:NEVER_GRANTED", "WHATSAPP:INSTITUTIONAL:NEVER_GRANTED", "WHATSAPP:MARKETING:GRANTED");
    }

    @Test
    void theHistoryIsTheNewestFirstAndAtMostOneHundredEvents() {
        Guardian guardian = guardian();
        for (int i = 0; i < 120; i++) {
            store.rows.add(new ConsentStore.StoredEvent(institutionUuid, new ConsentEvent(UUID.randomUUID(),
                    guardian.id(), com.confia.guardians.domain.ConsentChannel.EMAIL,
                    com.confia.guardians.domain.ConsentPurpose.MARKETING,
                    i % 2 == 0 ? com.confia.guardians.domain.ConsentAction.GRANT
                            : com.confia.guardians.domain.ConsentAction.WITHDRAW,
                    com.confia.guardians.domain.EvidenceSource.ADMIN_REGISTERED, i % 2 == 0 ? "2026-01" : null,
                    NOW_MICROS.plusSeconds(i), actor.accountId())));
        }

        GuardianConsentsView view = consents.of(actor, requestId, guardian.id());

        assertThat(view.history()).hasSize(ManageGuardianConsents.HISTORY_LIMIT).hasSize(100);
        assertThat(view.history()).extracting(GuardianConsentsView.Event::occurredAt)
                .isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(view.history().get(0).occurredAt()).isEqualTo(NOW_MICROS.plusSeconds(119));
        assertThat(view.states().get(1).state()).as("the latest event decides").isEqualTo("WITHDRAWN");
    }

    // --- Idempotency ---

    @Test
    void repeatingTheCurrentActionWritesNoEventNoAuditEntryAndAnswersTheCurrentStanding() {
        Guardian guardian = guardian();
        grant(guardian, "EMAIL", "MARKETING");
        withdraw(guardian, "SMS", "MARKETING");
        int events = store.rows.size();
        int entries = audit.size();

        ConsentChangeView repeatedGrant = grant(guardian, "EMAIL", "MARKETING");
        ConsentChangeView repeatedWithdraw = withdraw(guardian, "SMS", "MARKETING");

        assertThat(repeatedGrant.changed()).isFalse();
        assertThat(repeatedGrant.standing().state()).isEqualTo("GRANTED");
        assertThat(repeatedGrant.standing().policyVersion()).isEqualTo("2026-01");
        assertThat(repeatedWithdraw.changed()).isFalse();
        assertThat(repeatedWithdraw.standing().state()).isEqualTo("WITHDRAWN");
        assertThat(store.rows).hasSize(events);
        assertThat(audit).hasSize(entries);
        assertThat(committed()).isTrue();
    }

    @Test
    void withdrawingWhatWasNeverGrantedIsRecordedAndANewGrantAfterItIsPossible() {
        Guardian guardian = guardian();

        ConsentChangeView withdrawn = withdraw(guardian, "WHATSAPP", "INSTITUTIONAL");

        assertThat(withdrawn.changed()).isTrue();
        assertThat(withdrawn.standing().state()).isEqualTo("WITHDRAWN");
        assertThat(grant(guardian, "WHATSAPP", "INSTITUTIONAL").standing().state()).isEqualTo("GRANTED");
    }

    // --- Rules ---

    @Test
    void theTransactionalPurposeIsRefusedWithItsFieldBeforeAnyTransactionOrLock() {
        Guardian guardian = guardian();

        for (String action : List.of("GRANT", "WITHDRAW")) {
            assertThatThrownBy(() -> consents.record(actor, requestId, guardian.id(), "EMAIL", "TRANSACTIONAL", action,
                    PAPER, action.equals("GRANT") ? "2026-01" : null))
                    .isInstanceOfSatisfying(RejectedConsentException.class, e -> {
                        assertThat(e.field()).isEqualTo("purpose");
                        assertThat(e.reason()).isEqualTo("not-consentable");
                        assertThat(e.code()).isEqualTo("validation-failed");
                    });
        }
        assertThat(world.events).isEmpty();
        assertThat(transactionCalls).isEmpty();
        assertThat(store.rows).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void thePortalEvidenceAndABadOrMissingPolicyVersionAreRefusedWithTheirFieldAndNothingIsWritten() {
        Guardian guardian = guardian();

        assertRejected(() -> consents.record(actor, requestId, guardian.id(), "EMAIL", "MARKETING", "GRANT",
                "PORTAL_FORM", "2026-01"), "evidenceSource", "not-allowed");
        assertRejected(() -> consents.record(actor, requestId, guardian.id(), "EMAIL", "MARKETING", "GRANT", PAPER,
                null), "policyVersion", "required");
        assertRejected(() -> consents.record(actor, requestId, guardian.id(), "EMAIL", "MARKETING", "GRANT", PAPER,
                "2026 01"), "policyVersion", "invalid");
        assertRejected(() -> consents.record(actor, requestId, guardian.id(), "EMAIL", "MARKETING", "GRANT", PAPER,
                "a".repeat(33)), "policyVersion", "invalid");
        assertRejected(() -> consents.record(actor, requestId, guardian.id(), "EMAIL", "MARKETING", "WITHDRAW", ADMIN,
                "2026-01"), "policyVersion", "not-allowed");
        assertRejected(() -> consents.record(actor, requestId, guardian.id(), "FAX", "MARKETING", "GRANT", PAPER,
                "2026-01"), "channel", "invalid");
        assertRejected(() -> consents.record(actor, requestId, guardian.id(), "EMAIL", "MARKETING", "REVOKE", PAPER,
                null), "action", "invalid");
        assertThat(world.events).isEmpty();
        assertThat(transactionCalls).isEmpty();
        assertThat(store.rows).isEmpty();
    }

    private static void assertRejected(Runnable call, String field, String reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(RejectedConsentException.class, e -> {
            assertThat(e.field()).isEqualTo(field);
            assertThat(e.reason()).isEqualTo(reason);
        });
    }

    @Test
    void aGrantNeedsTheContactDataOfTheChannelAndAWithdrawalDoesNot() {
        Guardian onlyEmail = guardian("solo@example.org", null, GuardianStatus.ACTIVE);

        assertThatThrownBy(() -> grant(onlyEmail, "SMS", "INSTITUTIONAL"))
                .isInstanceOf(ConsentChannelUnavailableException.class);
        assertThatThrownBy(() -> grant(onlyEmail, "WHATSAPP", "MARKETING"))
                .isInstanceOf(ConsentChannelUnavailableException.class);
        assertThat(store.rows).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(transactionCalls).contains("rollback").doesNotContain("commit");

        assertThat(grant(onlyEmail, "EMAIL", "INSTITUTIONAL").changed()).isTrue();
        assertThat(withdraw(onlyEmail, "SMS", "INSTITUTIONAL").changed()).isTrue();
    }

    @Test
    void anInactiveGuardianCannotGrantButCanAlwaysWithdraw() {
        Guardian active = guardian();
        grant(active, "EMAIL", "INSTITUTIONAL");
        world.arrange(institution, new Guardian(active.id(), active.guardianCode(), active.firstName(),
                active.lastName(), false, false, active.email(), active.phone(), null, PreferredChannel.NONE,
                GuardianStatus.INACTIVE, NOW_MICROS, NOW_MICROS));
        int events = store.rows.size();

        assertThatThrownBy(() -> grant(active, "SMS", "INSTITUTIONAL")).isInstanceOf(GuardianNotActiveException.class);
        assertThat(store.rows).hasSize(events);

        ConsentChangeView withdrawn = withdraw(active, "EMAIL", "INSTITUTIONAL");
        assertThat(withdrawn.changed()).isTrue();
        assertThat(withdrawn.standing().state()).isEqualTo("WITHDRAWN");
        assertThat(consents.of(actor, requestId, active.id()).states().get(0).state()).isEqualTo("WITHDRAWN");
    }

    // --- Concurrency (the order of the locks) ---

    @Test
    void aChangeLocksTheGuardianRowBeforeItReadsTheStateAndBeforeItAppends() {
        Guardian guardian = guardian();

        grant(guardian, "EMAIL", "INSTITUTIONAL");

        assertThat(world.events).containsExactly("lock-guardian", "read-consents", "append-consent");
    }

    @Test
    void aRepeatStillLocksAndReadsButAppendsNothing() {
        Guardian guardian = guardian();
        grant(guardian, "EMAIL", "INSTITUTIONAL");
        world.events.clear();

        grant(guardian, "EMAIL", "INSTITUTIONAL");

        assertThat(world.events).containsExactly("lock-guardian", "read-consents");
    }

    @Test
    void aReadTakesNoLockAndRunsInAReadOnlyTransaction() {
        Guardian guardian = guardian();

        consents.of(actor, requestId, guardian.id());

        assertThat(world.events).doesNotContain("lock-guardian", "append-consent");
        assertThat(readOnlyFlags).contains("true");
    }

    @Test
    void aWriteIsNotReadOnlyAndCommits() {
        Guardian guardian = guardian();

        grant(guardian, "EMAIL", "INSTITUTIONAL");

        assertThat(readOnlyFlags).doesNotContain("true");
        assertThat(committed()).isTrue();
    }

    // --- Isolation ---

    @Test
    void aGuardianOfAnotherInstitutionIsNotFoundForTheReadAndForTheChangeAndNothingIsWritten() {
        UUID otherInstitution = UUID.randomUUID();
        AuthenticatedActor stranger = actorOf(otherInstitution);
        Guardian mine = guardian();
        grant(mine, "EMAIL", "INSTITUTIONAL");
        int events = store.rows.size();

        assertThatThrownBy(() -> consents.of(stranger, requestId, mine.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> consents.record(stranger, requestId, mine.id(), "EMAIL", "INSTITUTIONAL", "WITHDRAW",
                ADMIN, null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> consents.record(actor, requestId, UUID.randomUUID(), "EMAIL", "INSTITUTIONAL",
                "WITHDRAW", ADMIN, null)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(store.rows).hasSize(events);
        assertThat(consents.of(actor, requestId, mine.id()).states().get(0).state()).isEqualTo("GRANTED");
    }

    // --- Audit ---

    @Test
    void everyEventLeavesOneEntryWithGuardianChannelPurposeVersionAndEvidenceAndNeverContactDataOrNames() {
        Guardian guardian = guardian();

        grant(guardian, "EMAIL", "INSTITUTIONAL");
        withdraw(guardian, "EMAIL", "INSTITUTIONAL");

        assertThat(audit).hasSize(2);
        AuditEntry granted = audit.get(0);
        assertThat(granted.action()).isEqualTo("guardians.consent.granted");
        assertThat(granted.entityType()).isEqualTo("guardians.consent");
        assertThat(granted.entityId()).isEqualTo(guardian.id().toString());
        assertThat(granted.outcome()).isEqualTo("success");
        assertThat(granted.actorId()).isEqualTo(actor.accountId());
        assertThat(granted.institutionId()).isEqualTo(institutionUuid);
        JsonNode grantedAfter = json(granted.afterValue());
        assertThat(grantedAfter.get("guardianCode").asString()).isEqualTo("P-000001");
        assertThat(grantedAfter.get("channel").asString()).isEqualTo("EMAIL");
        assertThat(grantedAfter.get("purpose").asString()).isEqualTo("INSTITUTIONAL");
        assertThat(grantedAfter.get("policyVersion").asString()).isEqualTo("2026-01");
        assertThat(grantedAfter.get("evidenceSource").asString()).isEqualTo(PAPER);
        assertThat(grantedAfter.get("state").asString()).isEqualTo("GRANTED");
        assertThat(json(granted.beforeValue()).get("state").asString()).isEqualTo("NEVER_GRANTED");
        AuditEntry withdrawn = audit.get(1);
        assertThat(withdrawn.action()).isEqualTo("guardians.consent.withdrawn");
        assertThat(json(withdrawn.afterValue()).has("policyVersion")).isFalse();
        assertThat(json(withdrawn.afterValue()).get("state").asString()).isEqualTo("WITHDRAWN");
        assertThat(json(withdrawn.beforeValue()).get("state").asString()).isEqualTo("GRANTED");
        for (AuditEntry entry : audit) {
            String text = entry.toString() + entry.beforeValue() + entry.afterValue() + entry.action() + entry.reason();
            assertThat(text).doesNotContain("reservado@example.org").doesNotContain("99998888")
                    .doesNotContain("Nombre-Reservado").doesNotContain("Apellido-Reservado")
                    .doesNotContain("Direccion-Privada");
        }
    }

    @Test
    void aRefusedChangeLeavesNoAuditEntry() {
        Guardian inactive = guardian("a@example.org", "99998888", GuardianStatus.INACTIVE);

        assertThatThrownBy(() -> grant(inactive, "EMAIL", "INSTITUTIONAL"))
                .isInstanceOf(GuardianNotActiveException.class);

        assertThat(audit).isEmpty();
        assertThat(store.rows).isEmpty();
    }
}
