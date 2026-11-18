package com.confia.charges.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.charges.domain.ChargeAlreadyVoidedException;
import com.confia.charges.domain.ChargeNotVoidableException;
import com.confia.charges.domain.ChargeVoid;
import com.confia.charges.domain.ComputedCharge;
import com.confia.charges.domain.IdempotencyKeyRequiredException;
import com.confia.charges.domain.IntegrityCheck;
import com.confia.charges.domain.InvalidChargesValueException;
import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.ledger.application.api.LedgerPostingApi;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import com.confia.support.JdbcStubTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The void of a charge with the ports replaced by fakes: the order of the lock, the reversal and the record, the idempotency by
 * key and by content, what a refusal of the ledger leaves behind (nothing, and a rolled back transaction), the date of the
 * reversal and the validation. The SQL, the real ledger and the concurrency are proven against PostgreSQL in {@code
 * ChargeVoidIT}.
 */
class VoidChargeTest {

    private static final Instant NOW = Instant.parse("2027-02-10T18:00:00Z");
    private static final String REASON = "Cobro duplicado por error de la secretaria";

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final AuditLogWriter auditWriter = audit::add;
    private final UUID institutionUuid = UUID.randomUUID();
    private final InstitutionId institution = new InstitutionId(institutionUuid);
    private final AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), institutionUuid,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    private final String requestId = UUID.randomUUID().toString();
    private final UUID studentId = UUID.randomUUID();
    private final UUID chargeId = UUID.randomUUID();
    private final UUID chargeTransaction = UUID.randomUUID();

    private final FakeCharges charges = new FakeCharges();
    private final FakeVoids voids = new FakeVoids();
    private final FakeLedger ledger = new FakeLedger();

    private VoidCharge service(Instant now) {
        return new VoidCharge(JdbcStubTransactions.runner(transactionCalls), charges, voids, ledger, auditWriter,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private VoidCharge service() {
        return service(NOW);
    }

    private void aChargeOfFebruary() {
        charges.put(new ChargeRepository.ChargeForVoid(chargeId, studentId, chargeTransaction,
                Money.of("1650.00", CurrencyCode.HNL), LocalDate.parse("2027-02-01")));
    }

    @Test
    void locksTheAccountOfTheStudentBeforeReversingThenRecordsTheVoidAndAuditsWithoutTheReasonText() {
        aChargeOfFebruary();

        ChargeVoidView view = service().voidCharge(actor, requestId, "key-1", chargeId, "  " + REASON + "  ");

        assertThat(ledger.calls).containsExactly("lock", "reverse");
        assertThat(ledger.lockedKeys).containsExactly(new LedgerPostingApi.AccountKey(studentId, CurrencyCode.HNL));
        assertThat(ledger.reversal.originalTransactionId()).isEqualTo(chargeTransaction);
        assertThat(ledger.reversal.callerModule()).isEqualTo("charges");
        assertThat(ledger.reversal.idempotencyKey()).isEqualTo("charges:void:" + chargeId);
        assertThat(ledger.reversal.reason()).as("stripped").isEqualTo(REASON);
        assertThat(view.chargeId()).isEqualTo(chargeId);
        assertThat(view.reversalTransactionId()).isEqualTo(ledger.reversalId);
        assertThat(view.voidedBy()).isEqualTo(actor.accountId());
        assertThat(view.voidedAt()).isEqualTo(NOW);
        assertThat(voids.byKey).containsOnlyKeys("key-1");
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("charges.charge.voided");
            assertThat(entry.entityId()).isEqualTo(chargeId.toString());
            assertThat(entry.outcome()).isEqualTo("success");
            assertThat(entry.afterValue()).contains(view.id().toString(), studentId.toString(),
                    ledger.reversalId.toString(), "\"reasonLength\":" + REASON.length());
            assertThat(entry.afterValue()).doesNotContain("secretaria");
        });
        assertThat(transactionCalls).containsExactly("commit");
    }

    @Test
    void theReversalIsDatedTodayInHondurasAndNeverBeforeThePeriodOfTheCharge() {
        aChargeOfFebruary();
        // 03:00 UTC is 21:00 of the day before in Tegucigalpa.
        service(Instant.parse("2027-02-10T03:00:00Z")).voidCharge(actor, requestId, "key-1", chargeId, REASON);
        assertThat(ledger.reversal.effectiveOn()).isEqualTo(LocalDate.parse("2027-02-09"));

        UUID later = UUID.randomUUID();
        charges.put(new ChargeRepository.ChargeForVoid(later, studentId, UUID.randomUUID(),
                Money.of("10.00", CurrencyCode.HNL), LocalDate.parse("2027-03-01")));
        service().voidCharge(actor, requestId, "key-2", later, REASON);
        assertThat(ledger.reversal.effectiveOn()).as("not before its original").isEqualTo(LocalDate.parse("2027-03-01"));
    }

    @Test
    void theSameKeyAndTheSameRequestAnswerTheSameVoidAndWriteNothing() {
        aChargeOfFebruary();
        ChargeVoidView first = service().voidCharge(actor, requestId, "key-1", chargeId, REASON);
        ledger.calls.clear();
        audit.clear();

        ChargeVoidView again = service().voidCharge(actor, requestId, "key-1", chargeId, REASON);

        assertThat(again).isEqualTo(first);
        assertThat(ledger.calls).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(voids.inserts).isEqualTo(1);
    }

    @Test
    void theSameKeyWithAnotherReasonOrAnotherChargeIsRefused() {
        aChargeOfFebruary();
        service().voidCharge(actor, requestId, "key-1", chargeId, REASON);

        assertThatThrownBy(() -> service().voidCharge(actor, requestId, "key-1", chargeId,
                "Otro motivo distinto del primero que se dio"))
                        .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> service().voidCharge(actor, requestId, "key-1", UUID.randomUUID(), REASON))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThat(voids.inserts).isEqualTo(1);
    }

    @Test
    void anotherKeyOnAChargeThatIsAlreadyVoidedIsAConflictAndWritesNothing() {
        aChargeOfFebruary();
        service().voidCharge(actor, requestId, "key-1", chargeId, REASON);
        ledger.calls.clear();
        audit.clear();
        transactionCalls.clear();

        assertThatThrownBy(() -> service().voidCharge(actor, requestId, "key-2", chargeId, REASON))
                .isInstanceOf(ChargeAlreadyVoidedException.class);

        assertThat(ledger.calls).as("the lock is taken, the reversal is not asked").containsExactly("lock");
        assertThat(voids.inserts).isEqualTo(1);
        assertThat(audit).isEmpty();
        assertThat(transactionCalls).containsExactly("rollback");
    }

    @Test
    void aVoidThatAnotherRequestCommittedWhileThisOneWaitedForTheLockIsFoundUnderTheLock() {
        aChargeOfFebruary();
        ChargeVoid winner = new ChargeVoid(UUID.randomUUID(), chargeId, UUID.randomUUID(), REASON, "key-1",
                VoidCharge.hashOf(chargeId, com.confia.charges.domain.VoidReason.of(REASON)), actor.accountId(), NOW);
        ledger.onLock = () -> voids.put(winner);

        ChargeVoidView view = service().voidCharge(actor, requestId, "key-1", chargeId, REASON);

        assertThat(view.id()).isEqualTo(winner.id());
        assertThat(ledger.calls).containsExactly("lock");
        assertThat(voids.inserts).isZero();
    }

    @Test
    void aChargeVoidedByAnotherRequestWhileWaitingIsAConflictForAnotherKey() {
        aChargeOfFebruary();
        ledger.onLock = () -> voids.put(new ChargeVoid(UUID.randomUUID(), chargeId, UUID.randomUUID(), REASON,
                "other-key", "a".repeat(64), actor.accountId(), NOW));

        assertThatThrownBy(() -> service().voidCharge(actor, requestId, "key-1", chargeId, REASON))
                .isInstanceOf(ChargeAlreadyVoidedException.class);
        assertThat(ledger.calls).containsExactly("lock");
    }

    @Test
    void whenTheLedgerRefusesNothingIsRecordedAndTheTransactionRollsBack() {
        aChargeOfFebruary();
        for (LedgerPostingApi.ReversalRefusal refusal : LedgerPostingApi.ReversalRefusal.values()) {
            ledger.refusal = refusal;
            transactionCalls.clear();

            assertThatThrownBy(() -> service().voidCharge(actor, requestId, "key-" + refusal, chargeId, REASON))
                    .as(refusal.name()).isInstanceOf(ChargeNotVoidableException.class);

            assertThat(transactionCalls).containsExactly("rollback");
        }
        assertThat(voids.inserts).isZero();
        assertThat(audit).isEmpty();
    }

    @Test
    void aReversalThatTheLedgerFoundAlreadyPostedWithoutAVoidIsNotRecorded() {
        aChargeOfFebruary();
        ledger.created = false;

        assertThatThrownBy(() -> service().voidCharge(actor, requestId, "key-1", chargeId, REASON))
                .isInstanceOf(ChargeNotVoidableException.class);

        assertThat(voids.inserts).isZero();
        assertThat(audit).isEmpty();
    }

    @Test
    void aChargeThatIsNotInTheInstitutionIsNotFoundAndNothingIsLocked() {
        assertThatThrownBy(() -> service().voidCharge(actor, requestId, "key-1", UUID.randomUUID(), REASON))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(ledger.calls).isEmpty();
        assertThat(charges.askedInstitution).isEqualTo(institution);
    }

    @Test
    void theRequestIsValidatedBeforeAnythingIsTouched() {
        aChargeOfFebruary();

        assertThatThrownBy(() -> service().voidCharge(actor, requestId, null, chargeId, REASON))
                .isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThatThrownBy(() -> service().voidCharge(actor, requestId, "  ", chargeId, REASON))
                .isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThatThrownBy(() -> service().voidCharge(actor, requestId, "has space", chargeId, REASON))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service().voidCharge(actor, requestId, "k".repeat(129), chargeId, REASON))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service().voidCharge(actor, requestId, "key-1", chargeId, "muy corto"))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service().voidCharge(actor, requestId, "key-1", chargeId, null))
                .isInstanceOf(InvalidChargesValueException.class);

        assertThat(ledger.calls).isEmpty();
        assertThat(transactionCalls).isEmpty();
        assertThat(service().voidCharge(actor, requestId, "k".repeat(128), chargeId, REASON).chargeId())
                .as("128 characters is the longest key").isEqualTo(chargeId);
    }

    @Test
    void theHashDependsOnTheChargeAndOnTheReason() {
        var reason = com.confia.charges.domain.VoidReason.of(REASON);
        String hash = VoidCharge.hashOf(chargeId, reason);

        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(VoidCharge.hashOf(chargeId, reason)).isEqualTo(hash);
        assertThat(VoidCharge.hashOf(UUID.randomUUID(), reason)).isNotEqualTo(hash);
        assertThat(VoidCharge.hashOf(chargeId, com.confia.charges.domain.VoidReason.of(REASON + "."))).isNotEqualTo(hash);
    }

    // --- Fakes ---

    private static final class FakeCharges implements ChargeRepository {

        private final Map<UUID, ChargeForVoid> byId = new HashMap<>();
        InstitutionId askedInstitution;

        void put(ChargeForVoid charge) {
            byId.put(charge.id(), charge);
        }

        @Override
        public Optional<ChargeForVoid> findForVoid(InstitutionId institution, UUID chargeId) {
            askedInstitution = institution;
            return Optional.ofNullable(byId.get(chargeId));
        }

        @Override
        public List<ExistingCharge> findOverlapping(InstitutionId institution, UUID studentId, UUID paymentConceptId,
                LocalDate periodStart, LocalDate periodEnd) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertAutomatic(InstitutionId institution, UUID chargeId, ComputedCharge charge, UUID runId,
                UUID ledgerTransactionId, UUID createdBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CurrencyTotals> totalsOfRun(InstitutionId institution, UUID runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<UUID, StatementCharge> findByLedgerTransactions(InstitutionId institution,
                Collection<UUID> ledgerTransactionIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<ChargeRow> listByStudent(InstitutionId institution, UUID studentId, ChargeFilter filter,
                PageRequest page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IntegrityCheck.ChargeFact> integrityFacts(InstitutionId institution) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeVoids implements ChargeVoidRepository {

        final Map<String, ChargeVoid> byKey = new HashMap<>();
        final Map<UUID, ChargeVoid> byCharge = new HashMap<>();
        int inserts;

        void put(ChargeVoid charge) {
            byKey.put(charge.requestKey(), charge);
            byCharge.put(charge.chargeId(), charge);
        }

        @Override
        public void insert(InstitutionId institution, ChargeVoid charge) {
            inserts++;
            put(charge);
        }

        @Override
        public Optional<ChargeVoid> findByRequestKey(InstitutionId institution, String requestKey) {
            return Optional.ofNullable(byKey.get(requestKey));
        }

        @Override
        public Optional<ChargeVoid> findByChargeId(InstitutionId institution, UUID chargeId) {
            return Optional.ofNullable(byCharge.get(chargeId));
        }
    }

    private static final class FakeLedger implements LedgerPostingApi {

        final List<String> calls = new ArrayList<>();
        final List<AccountKey> lockedKeys = new ArrayList<>();
        final UUID reversalId = UUID.randomUUID();
        ChargeReversal reversal;
        ReversalRefusal refusal;
        boolean created = true;
        Runnable onLock = () -> {
        };

        @Override
        public Posted postCharge(Actor actor, ChargePosting posting) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<LockedAccount> lockStudentAccounts(Actor actor, Collection<AccountKey> keys) {
            calls.add("lock");
            lockedKeys.addAll(keys);
            onLock.run();
            return keys.stream().map(key -> new LockedAccount(key, UUID.randomUUID(), false)).toList();
        }

        @Override
        public Posted reverseCharge(Actor actor, ChargeReversal request) {
            calls.add("reverse");
            reversal = request;
            if (refusal != null) {
                throw new ReversalRefusedException(refusal);
            }
            return new Posted(reversalId, created);
        }
    }
}
