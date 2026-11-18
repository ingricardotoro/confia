package com.confia.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The restore path, the field rules and the published codes of the ledger domain, with fictional values. */
class LedgerTransactionRulesTest {

    private static final UUID RECEIVABLE = UUID.randomUUID();
    private static final UUID INCOME = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2027, 2, 1);
    private static final String REASON = "duplicated by the first run of the day";

    private static List<LedgerEntry> balanced() {
        Money amount = Money.of("10.00", CurrencyCode.HNL);
        return List.of(new LedgerEntry(RECEIVABLE, null, EntrySide.DEBIT, amount),
                new LedgerEntry(INCOME, null, EntrySide.CREDIT, amount));
    }

    private static LedgerTransaction post(TransactionKind kind, String source, String reason, String key) {
        return LedgerTransaction.post(UUID.randomUUID(), kind, DAY, source, null, reason, key, ACTOR, balanced());
    }

    @Test
    void restoreRebuildsTheSameTransactionAndExposesEveryField() {
        UUID id = UUID.randomUUID();
        UUID ref = UUID.randomUUID();
        LedgerTransaction restored = LedgerTransaction.restore(id, TransactionKind.ADJUSTMENT, DAY, "charges", ref, null,
                REASON, "k:1", ACTOR, balanced());

        assertThat(restored.id()).isEqualTo(id);
        assertThat(restored.kind()).isEqualTo(TransactionKind.ADJUSTMENT);
        assertThat(restored.effectiveOn()).isEqualTo(DAY);
        assertThat(restored.sourceModule()).isEqualTo("charges");
        assertThat(restored.sourceRefId()).isEqualTo(ref);
        assertThat(restored.reversesTransactionId()).isNull();
        assertThat(restored.reason()).isEqualTo(REASON);
        assertThat(restored.idempotencyKey()).isEqualTo("k:1");
        assertThat(restored.createdBy()).isEqualTo(ACTOR);
        assertThat(restored.entries()).hasSize(2);
    }

    @Test
    void restoreAppliesTheSameRules() {
        assertThatThrownBy(() -> LedgerTransaction.restore(UUID.randomUUID(), TransactionKind.CHARGE, DAY, "charges",
                null, UUID.randomUUID(), null, "k:1", ACTOR, balanced())).isInstanceOf(InvalidLedgerEntryException.class);
        assertThatThrownBy(() -> LedgerTransaction.restore(UUID.randomUUID(), TransactionKind.REVERSAL, DAY, "charges",
                null, null, REASON, "k:1", ACTOR, balanced())).isInstanceOf(InvalidLedgerEntryException.class);
    }

    @Test
    void theSourceModuleIsALowercaseIdentifier() {
        assertThatThrownBy(() -> post(TransactionKind.CHARGE, null, null, "k:1"))
                .isInstanceOf(InvalidLedgerEntryException.class);
        assertThatThrownBy(() -> post(TransactionKind.CHARGE, "Charges", null, "k:1"))
                .isInstanceOf(InvalidLedgerEntryException.class);
    }

    @Test
    void theIdempotencyKeyIsRequiredAndBounded() {
        assertThatThrownBy(() -> post(TransactionKind.CHARGE, "charges", null, null))
                .isInstanceOf(InvalidLedgerEntryException.class);
        assertThatThrownBy(() -> post(TransactionKind.CHARGE, "charges", null, "has space"))
                .isInstanceOf(InvalidLedgerEntryException.class);
        assertThatThrownBy(() -> post(TransactionKind.CHARGE, "charges", null, "k".repeat(201)))
                .isInstanceOf(InvalidLedgerEntryException.class);
    }

    @Test
    void anAdjustmentNeedsAReasonAndAnOptionalReasonIsBounded() {
        assertThatThrownBy(() -> post(TransactionKind.ADJUSTMENT, "charges", null, "k:1"))
                .isInstanceOf(InvalidLedgerEntryException.class);
        assertThatThrownBy(() -> post(TransactionKind.CHARGE, "charges", "too short", "k:1"))
                .isInstanceOf(InvalidLedgerEntryException.class);
        assertThatThrownBy(() -> post(TransactionKind.CHARGE, "charges", "x".repeat(501), "k:1"))
                .isInstanceOf(InvalidLedgerEntryException.class);
        assertThat(post(TransactionKind.CHARGE, "charges", REASON, "k:1").reason()).isEqualTo(REASON);
        assertThat(post(TransactionKind.CHARGE, "charges", null, "k:1").reason()).isNull();
    }

    @Test
    void theCanonicalFormNamesTheOptionalPartsWithADash() {
        assertThat(post(TransactionKind.CHARGE, "charges", null, "k:1").canonicalForm())
                .contains("|charges|-|-|-|");
        assertThat(post(TransactionKind.CHARGE, "charges", REASON, "k:1").canonicalForm()).contains("|" + REASON + "|");
    }

    @Test
    void theErrorsKeepTheirPublishedCodes() {
        assertThat(new LedgerIdempotencyConflictException().code()).isEqualTo("ledger-idempotency-conflict");
        assertThat(new TransactionAlreadyReversedException().code()).isEqualTo("transaction-already-reversed");
        assertThat(new StudentAccountUnderReviewException().code()).isEqualTo("student-account-under-review");
    }
}
