package com.confia.charges.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.charges.domain.IntegrityCheck.ChargeFact;
import com.confia.charges.domain.IntegrityCheck.MovementFact;
import com.confia.charges.domain.IntegrityCheck.Outcome;
import com.confia.charges.domain.IntegrityCheck.PostingFact;
import com.confia.charges.domain.IntegrityCheck.Reading;
import com.confia.charges.domain.IntegrityCheck.UnbalancedFact;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The nightly comparison of the charges with the ledger: clean data is sound and every kind of damage has its own finding. */
class IntegrityCheckTest {

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    private static Money usd(String amount) {
        return Money.of(amount, CurrencyCode.USD);
    }

    /** A charge and the ledger facts that make it sound. */
    private static final class World {

        final List<ChargeFact> charges = new ArrayList<>();
        final List<PostingFact> postings = new ArrayList<>();
        final List<UnbalancedFact> unbalanced = new ArrayList<>();
        final List<MovementFact> movements = new ArrayList<>();

        ChargeFact active(String total) {
            ChargeFact charge = new ChargeFact(UUID.randomUUID(), UUID.randomUUID(), hnl(total), null);
            charges.add(charge);
            postings.add(new PostingFact(charge.ledgerTransactionId(), "CHARGE", charge.chargeId(), null,
                    charge.total(), charge.total()));
            return charge;
        }

        ChargeFact voided(String total) {
            UUID reversal = UUID.randomUUID();
            ChargeFact charge = new ChargeFact(UUID.randomUUID(), UUID.randomUUID(), hnl(total), reversal);
            charges.add(charge);
            postings.add(new PostingFact(charge.ledgerTransactionId(), "CHARGE", charge.chargeId(), null,
                    charge.total(), charge.total()));
            postings.add(new PostingFact(reversal, "REVERSAL", charge.chargeId(), charge.ledgerTransactionId(),
                    charge.total(), charge.total()));
            return charge;
        }

        Outcome check() {
            return IntegrityCheck.evaluate(new Reading(charges, postings, unbalanced, movements(charges)));
        }

        /** The receivable movement the sound postings caused: each charge debits its total, each reversal credits it. */
        private List<MovementFact> movements(List<ChargeFact> all) {
            if (!movements.isEmpty()) {
                return movements;
            }
            Money debit = Money.zero(CurrencyCode.HNL);
            Money credit = Money.zero(CurrencyCode.HNL);
            for (ChargeFact charge : all) {
                debit = debit.add(charge.total());
                if (charge.voided()) {
                    credit = credit.add(charge.total());
                }
            }
            return List.of(new MovementFact(CurrencyCode.HNL, debit, credit));
        }
    }

    @Test
    void anInstitutionWithoutChargesIsSound() {
        Outcome outcome = new World().check();

        assertThat(outcome.sound()).isTrue();
        assertThat(outcome.chargesChecked()).isZero();
        assertThat(outcome.postingsChecked()).isZero();
    }

    @Test
    void activeAndVoidedChargesWithTheirPostingsAreSound() {
        World world = new World();
        world.active("1650.00");
        world.active("0.01");
        world.voided("500.00");

        Outcome outcome = world.check();

        assertThat(outcome.findings()).isEmpty();
        assertThat(outcome.chargesChecked()).isEqualTo(3);
        assertThat(outcome.postingsChecked()).isEqualTo(4);
        assertThat(outcome.countsByKind()).isEmpty();
    }

    @Test
    void anUnbalancedTransactionIsReportedWithBothSides() {
        World world = new World();
        world.active("100.00");
        UUID broken = UUID.randomUUID();
        world.unbalanced.add(new UnbalancedFact(broken, hnl("100.00"), hnl("99.99")));

        Outcome outcome = world.check();

        assertThat(outcome.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(IntegrityKind.UNBALANCED_TRANSACTION);
            assertThat(finding.ledgerTransactionId()).isEqualTo(broken);
            assertThat(finding.chargeId()).isNull();
            assertThat(finding.expected()).isEqualTo(hnl("100.00"));
            assertThat(finding.actual()).isEqualTo(hnl("99.99"));
        });
    }

    @Test
    void aChargeWithoutItsPostingIsMissingAndItsAmountDoesNotTieToTheReceivable() {
        World world = new World();
        ChargeFact charge = world.active("100.00");
        world.postings.clear();
        world.movements.add(new MovementFact(CurrencyCode.HNL, hnl("0.00"), hnl("0.00")));

        Outcome outcome = world.check();

        assertThat(outcome.findings()).extracting(IntegrityFinding::kind)
                .containsExactly(IntegrityKind.CHARGE_POSTING_MISSING, IntegrityKind.RECEIVABLE_MISMATCH);
        IntegrityFinding missing = outcome.findings().get(0);
        assertThat(missing.chargeId()).isEqualTo(charge.chargeId());
        assertThat(missing.expected()).isEqualTo(hnl("100.00"));
        assertThat(missing.actual().isZero()).isTrue();
    }

    @Test
    void aChargePointingAtAnotherTransactionThanTheOneInTheLedgerIsMissingAndTheStrayOneIsADuplicate() {
        World world = new World();
        ChargeFact charge = world.active("100.00");
        UUID stray = UUID.randomUUID();
        world.postings.set(0, new PostingFact(stray, "CHARGE", charge.chargeId(), null, hnl("100.00"), hnl("100.00")));

        Outcome outcome = world.check();

        assertThat(outcome.findings()).extracting(IntegrityFinding::kind)
                .containsExactlyInAnyOrder(IntegrityKind.CHARGE_POSTING_MISSING, IntegrityKind.CHARGE_POSTING_DUPLICATE);
    }

    @Test
    void twoChargeTransactionsForOneChargeAreADuplicate() {
        World world = new World();
        ChargeFact charge = world.active("100.00");
        UUID second = UUID.randomUUID();
        world.postings.add(new PostingFact(second, "CHARGE", charge.chargeId(), null, hnl("100.00"), hnl("100.00")));
        world.movements.add(new MovementFact(CurrencyCode.HNL, hnl("200.00"), hnl("0.00")));

        Outcome outcome = world.check();

        assertThat(outcome.findings()).extracting(IntegrityFinding::kind)
                .contains(IntegrityKind.CHARGE_POSTING_DUPLICATE);
        assertThat(outcome.findings()).filteredOn(f -> f.kind() == IntegrityKind.CHARGE_POSTING_DUPLICATE)
                .singleElement().satisfies(f -> assertThat(f.ledgerTransactionId()).isEqualTo(second));
    }

    @Test
    void aPostingWhoseTotalIsNotTheChargeTotalIsAMismatch() {
        World world = new World();
        ChargeFact charge = world.active("100.00");
        world.postings.set(0, new PostingFact(charge.ledgerTransactionId(), "CHARGE", charge.chargeId(), null,
                hnl("100.01"), hnl("100.01")));
        world.movements.add(new MovementFact(CurrencyCode.HNL, hnl("100.00"), hnl("0.00")));

        Outcome outcome = world.check();

        assertThat(outcome.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(IntegrityKind.CHARGE_TOTAL_MISMATCH);
            assertThat(finding.expected()).isEqualTo(hnl("100.00"));
            assertThat(finding.actual()).isEqualTo(hnl("100.01"));
        });
    }

    @Test
    void aPostingInAnotherCurrencyThanTheChargeIsAMismatchEvenWithTheSameAmount() {
        World world = new World();
        ChargeFact charge = world.active("100.00");
        world.postings.set(0, new PostingFact(charge.ledgerTransactionId(), "CHARGE", charge.chargeId(), null,
                usd("100.00"), usd("100.00")));
        world.movements.add(new MovementFact(CurrencyCode.HNL, hnl("100.00"), hnl("0.00")));

        assertThat(world.check().findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(IntegrityKind.CHARGE_TOTAL_MISMATCH);
            assertThat(finding.chargeId()).isEqualTo(charge.chargeId());
            assertThat(finding.expected()).as("two currencies cannot be set side by side").isNull();
            assertThat(finding.actual()).isNull();
        });
    }

    @Test
    void aVoidedChargeWithoutTheReversalItsVoidNamesIsMissingAndTheReceivableStillHoldsIt() {
        World world = new World();
        ChargeFact charge = world.voided("250.00");
        world.postings.removeIf(posting -> posting.kind().equals("REVERSAL"));
        world.movements.add(new MovementFact(CurrencyCode.HNL, hnl("250.00"), hnl("0.00")));

        Outcome outcome = world.check();

        assertThat(outcome.findings()).extracting(IntegrityFinding::kind)
                .containsExactly(IntegrityKind.VOID_REVERSAL_MISSING, IntegrityKind.RECEIVABLE_MISMATCH);
        assertThat(outcome.findings().get(0).ledgerTransactionId()).isEqualTo(charge.voidReversalTransactionId());
    }

    @Test
    void aVoidedChargeWhoseReversalIsForAnotherAmountIsAMismatch() {
        World world = new World();
        ChargeFact charge = world.voided("250.00");
        world.postings.set(1, new PostingFact(charge.voidReversalTransactionId(), "REVERSAL", charge.chargeId(),
                charge.ledgerTransactionId(), hnl("249.99"), hnl("249.99")));
        world.movements.add(new MovementFact(CurrencyCode.HNL, hnl("250.00"), hnl("250.00")));

        Outcome outcome = world.check();

        assertThat(outcome.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(IntegrityKind.CHARGE_TOTAL_MISMATCH);
            assertThat(finding.ledgerTransactionId()).isEqualTo(charge.voidReversalTransactionId());
        });
    }

    @Test
    void aSecondReversalOfAVoidedChargeIsADuplicate() {
        World world = new World();
        ChargeFact charge = world.voided("250.00");
        UUID second = UUID.randomUUID();
        world.postings.add(new PostingFact(second, "REVERSAL", charge.chargeId(), charge.ledgerTransactionId(),
                hnl("250.00"), hnl("250.00")));
        world.movements.add(new MovementFact(CurrencyCode.HNL, hnl("250.00"), hnl("500.00")));

        Outcome outcome = world.check();

        assertThat(outcome.findings()).extracting(IntegrityFinding::kind)
                .contains(IntegrityKind.VOID_REVERSAL_DUPLICATE, IntegrityKind.RECEIVABLE_MISMATCH);
    }

    @Test
    void aReversalOfAChargeThatHasNoVoidIsReportedAndSoIsTheReceivableThatNoLongerTiesToIt() {
        World world = new World();
        ChargeFact charge = world.active("80.00");
        UUID reversal = UUID.randomUUID();
        world.postings.add(new PostingFact(reversal, "REVERSAL", charge.chargeId(), charge.ledgerTransactionId(),
                hnl("80.00"), hnl("80.00")));
        world.movements.add(new MovementFact(CurrencyCode.HNL, hnl("80.00"), hnl("80.00")));

        Outcome outcome = world.check();

        assertThat(outcome.findings()).extracting(IntegrityFinding::kind)
                .containsExactly(IntegrityKind.REVERSAL_WITHOUT_VOID, IntegrityKind.RECEIVABLE_MISMATCH);
        assertThat(outcome.findings().get(0).ledgerTransactionId()).isEqualTo(reversal);
    }

    @Test
    void aChargeTransactionNoChargeExplainsIsAnOrphan() {
        World world = new World();
        world.active("10.00");
        UUID orphan = UUID.randomUUID();
        world.postings.add(new PostingFact(orphan, "CHARGE", UUID.randomUUID(), null, hnl("30.00"), hnl("30.00")));
        world.movements.add(new MovementFact(CurrencyCode.HNL, hnl("40.00"), hnl("0.00")));

        Outcome outcome = world.check();

        assertThat(outcome.findings()).extracting(IntegrityFinding::kind)
                .containsExactly(IntegrityKind.ORPHAN_POSTING, IntegrityKind.RECEIVABLE_MISMATCH);
        assertThat(outcome.findings().get(0).ledgerTransactionId()).isEqualTo(orphan);
    }

    @Test
    void aChargePostingWithoutASourceReferenceAndAReversalOfNothingAreOrphansAndAreReportedOnce() {
        World world = new World();
        UUID withoutRef = UUID.randomUUID();
        UUID reversalOfNothing = UUID.randomUUID();
        world.postings.add(new PostingFact(withoutRef, "CHARGE", null, null, hnl("1.00"), hnl("1.00")));
        world.postings.add(new PostingFact(reversalOfNothing, "REVERSAL", null, null, hnl("1.00"), hnl("1.00")));
        world.postings.add(new PostingFact(UUID.randomUUID(), "REVERSAL", UUID.randomUUID(), UUID.randomUUID(),
                hnl("1.00"), hnl("1.00")));
        world.movements.add(new MovementFact(CurrencyCode.HNL, hnl("0.00"), hnl("0.00")));

        Outcome outcome = world.check();

        assertThat(outcome.findings()).extracting(IntegrityFinding::kind)
                .containsOnly(IntegrityKind.ORPHAN_POSTING).hasSize(3);
    }

    @Test
    void otherKindsOfTransactionOfTheModuleAreNotOrphans() {
        World world = new World();
        world.postings.add(new PostingFact(UUID.randomUUID(), "DISCOUNT", UUID.randomUUID(), null, hnl("5.00"),
                hnl("5.00")));
        world.movements.add(new MovementFact(CurrencyCode.HNL, hnl("0.00"), hnl("0.00")));

        assertThat(world.check().findings()).isEmpty();
    }

    @Test
    void theReceivableIsComparedPerCurrencyAndACurrencyOnlyTheLedgerHasIsReported() {
        World world = new World();
        world.active("100.00");
        world.movements.add(new MovementFact(CurrencyCode.HNL, hnl("100.00"), hnl("0.00")));
        world.movements.add(new MovementFact(CurrencyCode.USD, usd("7.00"), usd("2.00")));

        Outcome outcome = world.check();

        assertThat(outcome.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(IntegrityKind.RECEIVABLE_MISMATCH);
            assertThat(finding.currency()).isEqualTo(CurrencyCode.USD);
            assertThat(finding.expected().isZero()).isTrue();
            assertThat(finding.actual()).isEqualTo(usd("5.00"));
            assertThat(finding.chargeId()).isNull();
        });
    }

    @Test
    void theReceivableUsesExactAmountsWithNoRoundingDrift() {
        World world = new World();
        for (int i = 0; i < 1000; i++) {
            world.active("0.01");
        }

        assertThat(world.check().findings()).isEmpty();
        world.movements.add(new MovementFact(CurrencyCode.HNL, hnl("10.01"), hnl("0.00")));
        assertThat(world.check().findings()).extracting(IntegrityFinding::kind)
                .containsExactly(IntegrityKind.RECEIVABLE_MISMATCH);
    }

    @Test
    void findingsComeInAStableOrderByKindThenSubject() {
        World world = new World();
        world.active("1.00");
        world.unbalanced.add(new UnbalancedFact(UUID.randomUUID(), hnl("2.00"), hnl("1.00")));
        world.postings.add(new PostingFact(UUID.randomUUID(), "CHARGE", UUID.randomUUID(), null, hnl("1.00"),
                hnl("1.00")));
        world.movements.add(new MovementFact(CurrencyCode.HNL, hnl("1.00"), hnl("0.00")));

        List<IntegrityKind> kinds = world.check().findings().stream().map(IntegrityFinding::kind).toList();

        assertThat(kinds).isSorted();
        assertThat(kinds).containsExactly(IntegrityKind.UNBALANCED_TRANSACTION, IntegrityKind.ORPHAN_POSTING);
    }

    @Test
    void countsByKindOnlyListsTheKindsThatOccur() {
        World world = new World();
        world.unbalanced.add(new UnbalancedFact(UUID.randomUUID(), hnl("2.00"), hnl("1.00")));
        world.unbalanced.add(new UnbalancedFact(UUID.randomUUID(), hnl("3.00"), hnl("1.00")));
        world.movements.add(new MovementFact(CurrencyCode.HNL, hnl("0.00"), hnl("0.00")));

        assertThat(world.check().countsByKind()).containsOnlyKeys(IntegrityKind.UNBALANCED_TRANSACTION)
                .containsEntry(IntegrityKind.UNBALANCED_TRANSACTION, 2);
    }

    @Test
    void onlyWhatTwoReadingsAgreeOnIsConfirmedAndTheCountsAreTheSecondOnes() {
        IntegrityFinding persistent = new IntegrityFinding(IntegrityKind.ORPHAN_POSTING, null, UUID.randomUUID(), null,
                null);
        IntegrityFinding transientOne = new IntegrityFinding(IntegrityKind.ORPHAN_POSTING, null, UUID.randomUUID(), null,
                null);
        IntegrityFinding later = new IntegrityFinding(IntegrityKind.ORPHAN_POSTING, null, UUID.randomUUID(), null, null);
        Outcome first = new Outcome(10, 20, List.of(persistent, transientOne));
        Outcome second = new Outcome(11, 22, List.of(persistent, later));

        Outcome confirmed = IntegrityCheck.confirmed(first, second);

        assertThat(confirmed.findings()).containsExactly(persistent);
        assertThat(confirmed.chargesChecked()).isEqualTo(11);
        assertThat(confirmed.postingsChecked()).isEqualTo(22);
    }

    @Test
    void aFindingIsTheSameFactInTwoReadingsEvenWhenItsAmountsMoved() {
        UUID charge = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        Outcome first = new Outcome(1, 1, List.of(new IntegrityFinding(IntegrityKind.CHARGE_TOTAL_MISMATCH, charge,
                transaction, hnl("10.00"), hnl("9.00"))));
        Outcome second = new Outcome(1, 1, List.of(new IntegrityFinding(IntegrityKind.CHARGE_TOTAL_MISMATCH, charge,
                transaction, hnl("10.00"), hnl("8.00"))));

        assertThat(IntegrityCheck.confirmed(first, second).findings()).hasSize(1);
        assertThat(IntegrityCheck.confirmed(first, new Outcome(1, 1, List.of())).findings()).isEmpty();
        assertThat(IntegrityCheck.confirmed(new Outcome(1, 1, List.of()), second).findings()).isEmpty();
    }

    @Test
    void theFactsOfTheLedgerKeepOneCurrencyEach() {
        assertThatThrownBy(() -> new PostingFact(UUID.randomUUID(), "CHARGE", null, null, hnl("1.00"), usd("1.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UnbalancedFact(UUID.randomUUID(), hnl("1.00"), usd("1.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MovementFact(CurrencyCode.HNL, usd("1.00"), hnl("1.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFindingNamesAChargeOrATransactionExceptTheReceivableMismatch() {
        assertThatThrownBy(() -> new IntegrityFinding(IntegrityKind.ORPHAN_POSTING, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrityFinding(IntegrityKind.RECEIVABLE_MISMATCH, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrityFinding(IntegrityKind.RECEIVABLE_MISMATCH, null, null, hnl("1.00"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrityFinding(IntegrityKind.CHARGE_TOTAL_MISMATCH, UUID.randomUUID(), null,
                hnl("1.00"), usd("1.00"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new IntegrityFinding(IntegrityKind.RECEIVABLE_MISMATCH, null, null, hnl("1.00"), hnl("2.00"))
                .currency()).isEqualTo(CurrencyCode.HNL);
        assertThat(new IntegrityFinding(IntegrityKind.ORPHAN_POSTING, null, UUID.randomUUID(), null, null).currency())
                .isNull();
    }
}
