package com.confia.charges.application;

import com.confia.charges.application.api.ChargePayments;
import com.confia.charges.domain.ChargeAlreadyVoidedException;
import com.confia.charges.domain.ChargeHasPaymentsException;
import com.confia.charges.domain.ChargeNotVoidableException;
import com.confia.charges.domain.ChargeVoid;
import com.confia.charges.domain.IdempotencyKeyRequiredException;
import com.confia.charges.domain.InvalidChargesValueException;
import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.charges.domain.VoidReason;
import com.confia.kernel.InstitutionId;
import com.confia.ledger.application.api.LedgerPostingApi;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.Digests;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import com.confia.shared.security.TransactionRunner;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The void of a charge (docs/cortes/charges-void-and-integrity.md). Nothing is deleted or edited: the ledger posts the exact
 * mirror of the transaction of the charge and a record of the void is inserted, both in ONE database transaction opened by the
 * transactional component, so either the charge is voided with its reversal or nothing happened.
 *
 * <p>Order, which is also the lock order of the accrual engine (the student sub-account first, ADR-0010): (1) the charge is
 * read; (2) the sub-account of the student is locked {@code FOR UPDATE}; (3) under the lock, the request key and the charge are
 * read again, so two simultaneous voids serialize and the second finds the first; (4) the ledger reverses; (5) the void is
 * inserted; (6) the audit entry is written. The body can run twice (retry on deadlock) and produces no effect outside the
 * transaction.
 *
 * <p>The {@code Idempotency-Key} is stored permanently on the void with the hash of what it asked: the same key and the same
 * request answer the same void, the same key and another request is refused, and another key on a charge that is already
 * voided is a conflict. The institution is the one of the actor, never a member of a request.
 */
public final class VoidCharge {

    static final String SOURCE_MODULE = "charges";

    /**
     * The reason the ledger keeps for the reversal. The free text the staff member typed may name a person, so it lives only
     * in the void record of {@code charges}; the ledger and its audit entry get this fixed sentence (20 to 500 characters).
     */
    static final String LEDGER_REASON = "Charge voided: see charges_charge_void";
    private static final String STAFF = "staff";
    private static final Pattern KEY = Pattern.compile("[!-~]{1,128}");
    private static final ZoneId INSTITUTION_ZONE = ZoneId.of("America/Tegucigalpa");

    private final TransactionRunner transactionRunner;
    private final ChargeRepository charges;
    private final ChargeVoidRepository voids;
    private final LedgerPostingApi ledger;
    private final ChargePayments payments;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public VoidCharge(TransactionRunner transactionRunner, ChargeRepository charges, ChargeVoidRepository voids,
            LedgerPostingApi ledger, ChargePayments payments, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.charges = Objects.requireNonNull(charges, "charges");
        this.voids = Objects.requireNonNull(voids, "voids");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Voids the charge.
     *
     * @param idempotencyKey the {@code Idempotency-Key} of the request, from 1 to 128 visible characters
     * @param reason why, from 20 to 500 characters
     * @throws IdempotencyKeyRequiredException when the key is missing
     * @throws IdempotencyPayloadMismatchException when the key was used for another request
     * @throws ChargeAlreadyVoidedException when the charge was voided with another key
     * @throws ChargeNotVoidableException when the ledger refuses the reversal
     * @throws ChargeHasPaymentsException when a payment has been allocated to the charge
     * @throws ResourceNotFoundException when the charge does not exist in the institution of the actor
     */
    public ChargeVoidView voidCharge(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID chargeId,
            String reason) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(chargeId, "chargeId");
        UUID auditRequestId = ChargesAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IdempotencyKeyRequiredException();
        }
        if (!KEY.matcher(idempotencyKey).matches()) {
            throw new InvalidChargesValueException();
        }
        VoidReason voidReason = VoidReason.of(reason);
        String hash = hashOf(actor.accountId(), chargeId, voidReason);
        InstitutionId institution = new InstitutionId(actor.institutionId());
        try {
            return attempt(actor, auditRequestId, institution, idempotencyKey, chargeId, voidReason, hash);
        } catch (ChargeVoidRepository.RequestKeyTakenException lostTheRace) {
            // Another request committed the same key first: the second pass finds it by key and answers a replay, or
            // refuses it as another request. A second loss is the same answer, never an internal error.
            try {
                return attempt(actor, auditRequestId, institution, idempotencyKey, chargeId, voidReason, hash);
            } catch (ChargeVoidRepository.RequestKeyTakenException again) {
                throw new IdempotencyPayloadMismatchException();
            }
        }
    }

    private ChargeVoidView attempt(AuthenticatedActor actor, UUID auditRequestId, InstitutionId institution,
            String idempotencyKey, UUID chargeId, VoidReason voidReason, String hash) {
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Optional<ChargeVoid> replay = voids.findByRequestKey(institution, idempotencyKey);
            if (replay.isPresent()) {
                return answerReplay(replay.get(), hash);
            }
            ChargeRepository.ChargeForVoid charge = charges.findForVoid(institution, chargeId)
                    .orElseThrow(ResourceNotFoundException::new);
            LedgerPostingApi.Actor ledgerActor = new LedgerPostingApi.Actor(institution.value(), actor.accountId(),
                    STAFF, auditRequestId);
            // The lock comes before the second reading: what another void committed while this one waited is seen now.
            ledger.lockStudentAccounts(ledgerActor,
                    List.of(new LedgerPostingApi.AccountKey(charge.studentId(), charge.total().currency())));
            Optional<ChargeVoid> underLockByKey = voids.findByRequestKey(institution, idempotencyKey);
            if (underLockByKey.isPresent()) {
                return answerReplay(underLockByKey.get(), hash);
            }
            if (voids.findByChargeId(institution, chargeId).isPresent()) {
                throw new ChargeAlreadyVoidedException();
            }
            // Under the same lock every payment of the student takes, so a payment that committed first is seen now (INV-17).
            if (payments.hasAllocations(institution, chargeId)) {
                throw new ChargeHasPaymentsException();
            }
            LocalDate today = LocalDate.now(clock.withZone(INSTITUTION_ZONE));
            LocalDate effectiveOn = today.isBefore(charge.periodStart()) ? charge.periodStart() : today;
            LedgerPostingApi.Posted reversal;
            try {
                reversal = ledger.reverseCharge(ledgerActor, new LedgerPostingApi.ChargeReversal(
                        charge.ledgerTransactionId(), SOURCE_MODULE, effectiveOn, LEDGER_REASON,
                        "charges:void:" + chargeId));
            } catch (LedgerPostingApi.ReversalRefusedException refused) {
                throw new ChargeNotVoidableException();
            }
            if (!reversal.created()) {
                // A reversal under this key exists but no void names it: the records disagree, and nothing is written.
                throw new ChargeNotVoidableException();
            }
            ChargeVoid recorded = new ChargeVoid(UUID.randomUUID(), chargeId, reversal.transactionId(),
                    voidReason.text(), idempotencyKey, hash, actor.accountId(), clock.instant());
            voids.insert(institution, recorded);
            auditLogWriter.append(ChargesAudit.chargeVoided(actor, auditRequestId, charge.studentId(), recorded));
            return ChargeVoidView.of(recorded);
        });
    }

    private static ChargeVoidView answerReplay(ChargeVoid existing, String hash) {
        if (!existing.answers(hash)) {
            throw new IdempotencyPayloadMismatchException();
        }
        return ChargeVoidView.of(existing);
    }

    /** The actor is part of what the key vouches for: another staff member with the same key and body gets no result. */
    static String hashOf(UUID actorId, UUID chargeId, VoidReason reason) {
        String text = "v2|" + actorId + "|" + chargeId + "|" + reason.text();
        return HexFormat.of().formatHex(Digests.sha256(text.getBytes(StandardCharsets.UTF_8)));
    }
}
