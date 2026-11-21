package com.confia.payments.application;

import com.confia.charges.application.api.PayableCharges;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.ledger.application.api.LedgerAccountDirectory;
import com.confia.ledger.application.api.LedgerAccountDirectory.LedgerAccountRef;
import com.confia.ledger.application.api.LedgerPostingApi;
import com.confia.payments.application.PostingAccountRepository.PostingAccountRow;
import com.confia.payments.domain.AllocationPlan;
import com.confia.payments.domain.OpenCharge;
import com.confia.payments.domain.PaymentAllocation;
import com.confia.payments.domain.PaymentAmounts;
import com.confia.payments.domain.PaymentMethod;
import com.confia.payments.domain.PostingAccounts;
import com.confia.payments.domain.PostingPurpose;
import com.confia.shared.security.AuthenticatedActor;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What registering cash and confirming a transfer have in common (docs/cortes/payments-register.md), always inside the
 * transaction of the use case: lock the sub-account of the student in the ledger (the lock every writer of the charges of the
 * student takes first, ADR-0010), read the open charges and what was already allocated to them under that lock, compute the plan
 * of allocation with the domain, post the {@code PAYMENT} transaction (debit cash or bank, credit the receivable of the student)
 * and save the allocations after the payment is stored. It does no arithmetic of its own: the sums are those of {@link Money}
 * and the spreading is {@link AllocationPlan}.
 */
final class PaymentPoster {

    /** The module of origin of the ledger transactions of the payments. */
    static final String SOURCE_MODULE = "payments";
    private static final String STAFF = "staff";

    private final LedgerPostingApi ledger;
    private final LedgerAccountDirectory ledgerAccounts;
    private final PayableCharges charges;
    private final PostingAccountRepository map;
    private final PaymentAllocationRepository allocations;
    private final Clock clock;

    PaymentPoster(LedgerPostingApi ledger, LedgerAccountDirectory ledgerAccounts, PayableCharges charges,
            PostingAccountRepository map, PaymentAllocationRepository allocations, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.ledgerAccounts = Objects.requireNonNull(ledgerAccounts, "ledgerAccounts");
        this.charges = Objects.requireNonNull(charges, "charges");
        this.map = Objects.requireNonNull(map, "map");
        this.allocations = Objects.requireNonNull(allocations, "allocations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    static LedgerPostingApi.Actor ledgerActor(AuthenticatedActor actor, UUID requestId) {
        return new LedgerPostingApi.Actor(actor.institutionId(), actor.accountId(), STAFF, requestId);
    }

    /**
     * The accounts a payment is posted to: cash and bank of the map and the receivable of the charges, each proven to exist, be
     * active and be of the right type. Any that is missing or unusable leaves the set incomplete, which refuses the posting.
     */
    PostingAccounts accounts(InstitutionId institution) {
        Map<PostingPurpose, UUID> mapped = map.listRows(institution).stream()
                .collect(Collectors.toMap(PostingAccountRow::purpose, PostingAccountRow::ledgerAccountId));
        UUID receivable = charges.receivableAccount(institution).orElse(null);
        Set<UUID> ids = new java.util.HashSet<>(mapped.values());
        if (receivable != null) {
            ids.add(receivable);
        }
        Map<UUID, LedgerAccountRef> found = ids.isEmpty() ? Map.of() : ledgerAccounts.find(institution, ids);
        return new PostingAccounts(usable(lookup(found, mapped.get(PostingPurpose.CASH)), PostingPurpose.CASH),
                usable(lookup(found, mapped.get(PostingPurpose.BANK)), PostingPurpose.BANK),
                receivableOf(lookup(found, receivable)));
    }

    private static LedgerAccountRef lookup(Map<UUID, LedgerAccountRef> found, UUID id) {
        return id == null ? null : found.get(id);
    }

    private static UUID usable(LedgerAccountRef account, PostingPurpose purpose) {
        return account != null && account.active() && purpose.accepts(account.type(), account.holdsStudentBalances())
                ? account.id() : null;
    }

    private static UUID receivableOf(LedgerAccountRef account) {
        return account != null && account.active()
                && PostingAccounts.acceptsReceivable(account.type(), account.holdsStudentBalances()) ? account.id() : null;
    }

    /** Opens the missing sub-account of the student and locks it {@code FOR UPDATE}. */
    void lockStudent(LedgerPostingApi.Actor actor, UUID studentId) {
        ledger.lockStudentAccounts(actor, List.of(new LedgerPostingApi.AccountKey(studentId, PaymentAmounts.CURRENCY)));
    }

    /** The plan for {@code amount} over the charges of the student that are active now and still have something pending. */
    AllocationPlan plan(InstitutionId institution, UUID studentId, Money amount) {
        List<PayableCharges.ActiveCharge> active = charges.activeCharges(institution, studentId, PaymentAmounts.CURRENCY);
        Map<UUID, Money> allocated = allocations.allocatedByCharge(institution,
                active.stream().map(PayableCharges.ActiveCharge::chargeId).toList());
        Money none = Money.zero(PaymentAmounts.CURRENCY);
        List<OpenCharge> open = new ArrayList<>();
        for (PayableCharges.ActiveCharge charge : active) {
            open.add(new OpenCharge(charge.chargeId(), charge.paymentConceptId(), charge.conceptCode(),
                    charge.periodStart(), charge.dueOn(), charge.total(), allocated.getOrDefault(charge.chargeId(), none)));
        }
        return AllocationPlan.of(amount, open);
    }

    /**
     * Posts the {@code PAYMENT} transaction dated the day the money was received: the account of the method is debited and the
     * receivable of the student credited, for the whole amount (what exceeds the open charges stays as a credit balance).
     *
     * @return the identifier of the ledger transaction
     */
    UUID post(LedgerPostingApi.Actor actor, PostingAccounts accounts, PaymentMethod method, UUID paymentId, UUID studentId,
            Money amount, LocalDate receivedOn) {
        LedgerPostingApi.Posted posted = ledger.postPayment(actor, new LedgerPostingApi.PaymentPosting(receivedOn,
                SOURCE_MODULE, paymentId, "payments:payment:" + paymentId,
                List.of(new LedgerPostingApi.Line(accounts.debitFor(method), null, LedgerPostingApi.Side.DEBIT, amount),
                        new LedgerPostingApi.Line(accounts.receivableAccount(), studentId, LedgerPostingApi.Side.CREDIT,
                                amount))));
        return posted.transactionId();
    }

    /** Stores the lines of the plan as allocations of the payment, which must already be stored and confirmed. */
    List<PaymentAllocation> save(InstitutionId institution, UUID paymentId, AllocationPlan plan) {
        List<PaymentAllocation> saved = new ArrayList<>();
        for (AllocationPlan.Line line : plan.lines()) {
            PaymentAllocation allocation = new PaymentAllocation(UUID.randomUUID(), paymentId, line.chargeId(),
                    line.amount(), clock.instant());
            allocations.insert(institution, allocation);
            saved.add(allocation);
        }
        return saved;
    }
}
