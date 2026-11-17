package com.confia.charges.application;

import com.confia.charges.domain.InactiveReferenceException;
import com.confia.charges.domain.InvalidChargesValueException;
import com.confia.charges.domain.PostingAccountInvalidException;
import com.confia.charges.domain.PostingAccountKey;
import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.charges.domain.StaleVersionException;
import com.confia.kernel.InstitutionId;
import com.confia.ledger.application.api.LedgerAccountDirectory;
import com.confia.ledger.application.api.LedgerAccountDirectory.LedgerAccountRef;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads and sets the map of posting accounts of the institution of the actor (docs/cortes/ledger-chart-of-accounts.md): which
 * account of the chart the receivable, the tax payable and the revenue of each category of concept use when a charge is posted.
 * The map is DATA. Setting it is a PARTIAL upsert: the entries of the request are the only ones written, so a map can be loaded
 * in steps and an incomplete one stays reportable (the accrual preview names it {@code posting-accounts-incomplete}); no entry
 * is ever deleted.
 *
 * <p>Every account named is read in the same transaction with {@code lockShare} of the ledger (in the order of their
 * identifiers), so it cannot be deactivated before the map that points at it commits; it must exist in the institution (a
 * foreign account is a 404), be active, and be of the type its purpose requires. Every entry names the version the editor read,
 * or none for an entry that does not exist yet; any other answers stale-version and nothing is written. Every entry that changes
 * leaves one {@code charges.posting_account.updated} in the audit trail in the same transaction.
 */
public final class ManagePostingAccounts {

    /** One entry of a request to set the map; {@code version} is null for an entry that does not exist yet. */
    public record Entry(String purpose, String conceptCategory, UUID ledgerAccountId, Long version) {
    }

    private static final int MAX_ENTRIES = 20;

    private final TransactionRunner transactionRunner;
    private final PostingAccountRepository map;
    private final LedgerAccountDirectory ledgerAccounts;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManagePostingAccounts(TransactionRunner transactionRunner, PostingAccountRepository map,
            LedgerAccountDirectory ledgerAccounts, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.map = Objects.requireNonNull(map, "map");
        this.ledgerAccounts = Objects.requireNonNull(ledgerAccounts, "ledgerAccounts");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<PostingAccountView> list(AuthenticatedActor actor, String requestId) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ChargesAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> map.listRows(institution).stream().map(PostingAccountView::of).toList());
    }

    /** Sets the entries and answers the whole map after the write. */
    public List<PostingAccountView> set(AuthenticatedActor actor, String requestId, List<Entry> entries) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ChargesAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        List<Parsed> parsed = parse(entries);
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Map<UUID, LedgerAccountRef> accounts = ledgerAccounts.lockShare(institution,
                    parsed.stream().map(Parsed::ledgerAccountId).collect(Collectors.toSet()));
            for (Parsed entry : parsed) {
                requireUsable(entry, accounts.get(entry.ledgerAccountId()));
            }
            Instant now = clock.instant();
            for (Parsed entry : parsed) {
                Optional<PostingAccountRow> existing = map.findRow(institution, entry.key());
                if (existing.isEmpty()) {
                    if (entry.version() != null) {
                        throw new StaleVersionException();
                    }
                    PostingAccountRow row = map.insert(institution, UUID.randomUUID(), entry.key(),
                            entry.ledgerAccountId(), now);
                    auditLogWriter.append(ChargesAudit.postingAccountSet(actor, auditRequestId, null, row));
                } else {
                    PostingAccountRow before = existing.get();
                    if (entry.version() == null || entry.version() != before.version()) {
                        throw new StaleVersionException();
                    }
                    if (!before.ledgerAccountId().equals(entry.ledgerAccountId())) {
                        PostingAccountRow after = map.replaceAccount(institution, before, entry.ledgerAccountId(),
                                before.version(), now);
                        auditLogWriter.append(ChargesAudit.postingAccountSet(actor, auditRequestId, before, after));
                    }
                }
            }
            return map.listRows(institution).stream().map(PostingAccountView::of).toList();
        });
    }

    private record Parsed(PostingAccountKey key, UUID ledgerAccountId, Long version) {
    }

    /** The syntax of the request: known purposes and categories, an account for each, no key twice, and a bounded size. */
    private static List<Parsed> parse(List<Entry> entries) {
        if (entries == null || entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            throw new InvalidChargesValueException();
        }
        Set<PostingAccountKey> seen = new HashSet<>();
        List<Parsed> parsed = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry == null || entry.ledgerAccountId() == null || (entry.version() != null && entry.version() < 0)) {
                throw new InvalidChargesValueException();
            }
            PostingAccountKey key = PostingAccountKey.of(entry.purpose(), entry.conceptCategory());
            if (!seen.add(key)) {
                throw new InvalidChargesValueException();
            }
            parsed.add(new Parsed(key, entry.ledgerAccountId(), entry.version()));
        }
        // A fixed order, so two concurrent sets write the rows in the same order and never deadlock.
        parsed.sort(Comparator.comparing((Parsed p) -> p.key().purpose())
                .thenComparing(p -> p.key().conceptCategory() == null ? "" : p.key().conceptCategory()));
        return parsed;
    }

    private static void requireUsable(Parsed entry, LedgerAccountRef account) {
        if (account == null) {
            throw new ResourceNotFoundException();
        }
        if (!account.active()) {
            throw new InactiveReferenceException();
        }
        if (!entry.key().purpose().accepts(account.type(), account.holdsStudentBalances())) {
            throw new PostingAccountInvalidException();
        }
    }
}
