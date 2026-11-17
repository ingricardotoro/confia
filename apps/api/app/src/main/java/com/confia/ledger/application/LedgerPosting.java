package com.confia.ledger.application;

import com.confia.kernel.InstitutionId;
import com.confia.ledger.domain.AccountRef;
import com.confia.ledger.domain.LedgerEntry;
import com.confia.ledger.domain.LedgerIdempotencyConflictException;
import com.confia.ledger.domain.LedgerResourceNotFoundException;
import com.confia.ledger.domain.LedgerTransaction;
import com.confia.ledger.domain.StudentAccountUnderReviewException;
import com.confia.ledger.domain.TransactionAlreadyReversedException;
import com.confia.ledger.domain.TransactionKind;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.Digests;
import com.confia.shared.security.TransactionRunner;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The public port of the ledger for the modules that write to it (docs/cortes/ledger-core.md): {@link #post} a balanced
 * transaction and {@link #reverse} one. Every call runs in one transaction of the transactional component, in this order:
 * (1) the idempotency key is looked up and its content hash compared (INV-24); (2) the missing student sub-accounts are
 * opened (INV-33); (3) every affected sub-account is locked with {@code FOR UPDATE} in the fixed order student, currency
 * (ADR-0010); (4) an account under review refuses the write; (5) header, entries and audit are inserted together. The
 * database verifies balance, mirror and immutability at COMMIT. Nothing here edits or deletes, and nothing here does
 * arithmetic beyond what {@code LedgerTransaction} and {@code Money} do.
 *
 * <p>The body can run twice (retry on deadlock) and produces no effect outside the transaction.
 */
public final class LedgerPosting {

    private final TransactionRunner transactionRunner;
    private final LedgerRepository ledger;
    private final AuditLogWriter auditLogWriter;

    public LedgerPosting(TransactionRunner transactionRunner, LedgerRepository ledger, AuditLogWriter auditLogWriter) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
    }

    /** What a source module asks to post. */
    public record PostCommand(TransactionKind kind, LocalDate effectiveOn, String sourceModule, UUID sourceRefId,
            String reason, String idempotencyKey, List<LedgerEntry> entries) {
    }

    /** What the source module asks to reverse: its own transaction, by id. */
    public record ReverseCommand(UUID originalTransactionId, String callerModule, LocalDate effectiveOn, String reason,
            String idempotencyKey) {
    }

    /** The outcome: the stored transaction and whether this call created it ({@code false} on a replay). */
    public record Posted(StoredTransaction stored, boolean created) {
    }

    public Posted post(LedgerActor actor, PostCommand command) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");
        return withRaceRetry(() -> transactionRunner.execute(actor.securityContext(), () -> doPost(actor, command)));
    }

    public Posted reverse(LedgerActor actor, ReverseCommand command) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");
        return withRaceRetry(() -> transactionRunner.execute(actor.securityContext(), () -> doReverse(actor, command)));
    }

    /**
     * Two calls with the same key at once: the loser waits on the unique index and fails with a race error when the
     * winner commits. Running it again finds the winner by key and replays it.
     */
    private Posted withRaceRetry(java.util.function.Supplier<Posted> call) {
        try {
            return call.get();
        } catch (ConcurrentIdempotentPostException first) {
            return call.get();
        }
    }

    private Posted doPost(LedgerActor actor, PostCommand command) {
        InstitutionId institution = new InstitutionId(actor.institutionId());
        LedgerTransaction transaction = LedgerTransaction.post(UUID.randomUUID(), command.kind(), command.effectiveOn(),
                command.sourceModule(), command.sourceRefId(), command.reason(), command.idempotencyKey(),
                actor.actorId(), command.entries());
        String hash = hashOf(transaction);
        Posted replay = replayOf(institution, transaction.idempotencyKey(), hash);
        if (replay != null) {
            return replay;
        }
        requireAccounts(institution, transaction, false);
        return insert(actor, institution, transaction, hash, Set.of());
    }

    private Posted doReverse(LedgerActor actor, ReverseCommand command) {
        InstitutionId institution = new InstitutionId(actor.institutionId());
        StoredTransaction original = ledger.findById(institution, command.originalTransactionId())
                .orElseThrow(LedgerResourceNotFoundException::new);
        LedgerTransaction reversal = LedgerTransaction.mirror(original.transaction(), UUID.randomUUID(),
                command.callerModule(), command.effectiveOn(), command.reason(), command.idempotencyKey(),
                actor.actorId());
        String hash = hashOf(reversal);
        Posted replay = replayOf(institution, reversal.idempotencyKey(), hash);
        if (replay != null) {
            return replay;
        }
        requireAccounts(institution, reversal, true);
        Posted posted = insert(actor, institution, reversal, hash, Set.of(original.transaction().id()));
        return posted;
    }

    private Posted replayOf(InstitutionId institution, String key, String hash) {
        return ledger.findByIdempotencyKey(institution, key).map(existing -> {
            if (!existing.payloadHash().equals(hash)) {
                throw new LedgerIdempotencyConflictException();
            }
            return new Posted(existing, false);
        }).orElse(null);
    }

    private void requireAccounts(InstitutionId institution, LedgerTransaction transaction, boolean mirror) {
        Set<UUID> ids = transaction.entries().stream().map(LedgerEntry::accountId).collect(Collectors.toSet());
        Map<UUID, AccountRef> found = new LinkedHashMap<>();
        ledger.findAccounts(institution, ids).forEach(account -> found.put(account.id(), account));
        for (LedgerEntry entry : transaction.entries()) {
            AccountRef account = found.get(entry.accountId());
            if (account == null) {
                throw new LedgerResourceNotFoundException();
            }
            account.requireUsableBy(entry, mirror);
        }
    }

    private Posted insert(LedgerActor actor, InstitutionId institution, LedgerTransaction transaction, String hash,
            Set<UUID> reversedOriginals) {
        Set<StudentCurrency> keys = new TreeSet<>();
        for (LedgerEntry entry : transaction.entries()) {
            if (entry.studentId() != null) {
                keys.add(new StudentCurrency(entry.studentId(), entry.amount().currency()));
            }
        }
        List<StudentAccountRef> locked = ledger.openAndLock(institution, List.copyOf(keys));
        Map<StudentCurrency, UUID> accounts = new LinkedHashMap<>();
        for (StudentAccountRef account : locked) {
            if (account.underReview()) {
                throw new StudentAccountUnderReviewException();
            }
            accounts.put(account.key(), account.id());
        }
        // After the lock a concurrent reversal of the same original has committed or does not exist.
        for (UUID original : reversedOriginals) {
            if (ledger.isReversed(institution, original)) {
                throw new TransactionAlreadyReversedException();
            }
        }
        ledger.insert(institution, transaction, hash, accounts);
        for (StudentAccountRef account : locked) {
            if (account.opened()) {
                auditLogWriter.append(LedgerAudit.studentAccountOpened(actor, account));
            }
        }
        auditLogWriter.append(transaction.kind() == TransactionKind.REVERSAL
                ? LedgerAudit.reversed(actor, transaction, hash)
                : LedgerAudit.posted(actor, transaction, hash));
        return new Posted(new StoredTransaction(transaction, hash, null), true);
    }

    private static String hashOf(LedgerTransaction transaction) {
        return HexFormat.of().formatHex(Digests.sha256(transaction.canonicalForm().getBytes(StandardCharsets.UTF_8)));
    }
}
