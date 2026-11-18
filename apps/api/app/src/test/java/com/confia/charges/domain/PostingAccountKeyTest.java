package com.confia.charges.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The key of a row of the posting map and the account type each purpose needs (docs/cortes/ledger-chart-of-accounts.md). */
class PostingAccountKeyTest {

    @Test
    void theRevenueMayHaveACategoryOrNoneAndTheOthersHaveNone() {
        assertThat(PostingAccountKey.of("REVENUE", "TUITION").conceptCategory()).isEqualTo("TUITION");
        assertThat(PostingAccountKey.of("REVENUE", null).conceptCategory()).isNull();
        assertThat(PostingAccountKey.of("REVENUE", "  ").conceptCategory()).as("blank is none").isNull();
        assertThat(PostingAccountKey.of("RECEIVABLE", null).purpose()).isEqualTo(PostingPurpose.RECEIVABLE);
        assertThat(PostingAccountKey.of("TAX_PAYABLE", null).purpose()).isEqualTo(PostingPurpose.TAX_PAYABLE);
        assertThatThrownBy(() -> PostingAccountKey.of("RECEIVABLE", "TUITION"))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> PostingAccountKey.of("TAX_PAYABLE", "OTHER"))
                .isInstanceOf(InvalidChargesValueException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tuition", "Tuition", "BOOKS", "TUITION ", "DEFAULT"})
    void aCategoryOutsideTheSetOfTheConceptsIsRefused(String category) {
        assertThatThrownBy(() -> PostingAccountKey.of("REVENUE", category))
                .isInstanceOf(InvalidChargesValueException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "receivable", "Revenue", "TAX", "EXPENSE"})
    void aPurposeOutsideTheSetOrWithOtherCapitalsIsRefused(String purpose) {
        assertThatThrownBy(() -> PostingAccountKey.of(purpose, null)).isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void aMissingPurposeIsRefused() {
        assertThatThrownBy(() -> PostingAccountKey.of(null, null)).isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void eachPurposeAcceptsExactlyTheTypeAndStudentFlagTheAccrualEngineRequires() {
        assertThat(PostingPurpose.RECEIVABLE.accepts("ASSET", true)).isTrue();
        assertThat(PostingPurpose.RECEIVABLE.accepts("ASSET", false)).isFalse();
        assertThat(PostingPurpose.RECEIVABLE.accepts("LIABILITY", true)).isFalse();
        assertThat(PostingPurpose.REVENUE.accepts("INCOME", false)).isTrue();
        assertThat(PostingPurpose.REVENUE.accepts("INCOME", true)).isFalse();
        assertThat(PostingPurpose.REVENUE.accepts("EXPENSE", false)).isFalse();
        assertThat(PostingPurpose.TAX_PAYABLE.accepts("LIABILITY", false)).isTrue();
        assertThat(PostingPurpose.TAX_PAYABLE.accepts("LIABILITY", true)).isFalse();
        assertThat(PostingPurpose.TAX_PAYABLE.accepts("ASSET", false)).isFalse();
        assertThat(new PostingAccountInvalidException().code()).isEqualTo("posting-account-invalid");
    }
}
