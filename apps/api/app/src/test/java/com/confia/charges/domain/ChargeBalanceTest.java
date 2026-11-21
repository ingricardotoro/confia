package com.confia.charges.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import org.junit.jupiter.api.Test;

/** What is paid and what is outstanding on a charge is derived, never stored (rule 4 of CLAUDE.md). */
class ChargeBalanceTest {

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    @Test
    void aChargeWithNoPaymentOwesItsWholeTotal() {
        ChargeBalance balance = ChargeBalance.of(hnl("1265.00"), Money.zero(CurrencyCode.HNL), false);

        assertThat(balance.paid().isZero()).isTrue();
        assertThat(balance.outstanding()).isEqualTo(hnl("1265.00"));
    }

    @Test
    void aPartialPaymentLeavesTheDifferenceOutstanding() {
        ChargeBalance balance = ChargeBalance.of(hnl("1265.00"), hnl("235.00"), false);

        assertThat(balance.paid()).isEqualTo(hnl("235.00"));
        assertThat(balance.outstanding()).isEqualTo(hnl("1030.00"));
    }

    @Test
    void anExactPaymentLeavesNothingOutstanding() {
        ChargeBalance balance = ChargeBalance.of(hnl("1265.00"), hnl("1265.00"), false);

        assertThat(balance.paid()).isEqualTo(hnl("1265.00"));
        assertThat(balance.outstanding().isZero()).isTrue();
    }

    @Test
    void whatIsOutstandingNeverGoesBelowZero() {
        assertThat(ChargeBalance.of(hnl("10.00"), hnl("10.01"), false).outstanding().isZero()).isTrue();
    }

    @Test
    void aVoidedChargeOwesNothingAndHasNothingPaid() {
        ChargeBalance balance = ChargeBalance.of(hnl("1265.00"), Money.zero(CurrencyCode.HNL), true);

        assertThat(balance.paid().isZero()).isTrue();
        assertThat(balance.outstanding().isZero()).isTrue();
    }
}
