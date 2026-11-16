package com.confia.shared.infrastructure.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InvalidMoneyAmountException;
import com.confia.kernel.Money;
import com.confia.kernel.UnsupportedCurrencyException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyColumnsTest {

    @Test
    void theColumnsBecomeMoneyAtScaleFourWhateverTheScaleTheDriverReturned() {
        assertThat(MoneyColumns.toMoney(new BigDecimal("1500"), "HNL"))
                .isEqualTo(Money.of("1500.0000", CurrencyCode.HNL));
        assertThat(MoneyColumns.toMoney(new BigDecimal("1500.5"), "USD").toPlainString()).isEqualTo("1500.5000");
        assertThat(MoneyColumns.toMoney(new BigDecimal("1.5E+3"), "HNL").toPlainString()).isEqualTo("1500.0000");
    }

    @Test
    void moneyBecomesTheColumnsAtScaleFourWithTheCurrencyName() {
        Money money = Money.of("12.30", CurrencyCode.USD);

        assertThat(MoneyColumns.amountColumn(money)).isEqualByComparingTo("12.30");
        assertThat(MoneyColumns.amountColumn(money).scale()).isEqualTo(4);
        assertThat(MoneyColumns.currencyColumn(money)).isEqualTo("USD");
    }

    @Test
    void aCurrencyOutsideTheEnabledSetOrWithOtherCapitalsIsRefused() {
        for (String currency : new String[] {"EUR", "hnl", "", "HNL "}) {
            assertThatThrownBy(() -> MoneyColumns.toMoney(BigDecimal.ONE, currency))
                    .isInstanceOf(UnsupportedCurrencyException.class);
        }
    }

    @Test
    void moreThanFourSignificantDecimalsAndAnOutOfRangeAmountAreRefused() {
        assertThatThrownBy(() -> MoneyColumns.toMoney(new BigDecimal("1.00001"), "HNL"))
                .isInstanceOf(InvalidMoneyAmountException.class);
        assertThatThrownBy(() -> MoneyColumns.toMoney(new BigDecimal("10000000000"), "HNL"))
                .isInstanceOf(InvalidMoneyAmountException.class);
    }

    @Test
    void aMissingColumnIsAProgrammingErrorAndNeverBecomesZero() {
        assertThatThrownBy(() -> MoneyColumns.toMoney(null, "HNL")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MoneyColumns.toMoney(BigDecimal.ONE, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MoneyColumns.amountColumn(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MoneyColumns.currencyColumn(null)).isInstanceOf(NullPointerException.class);
    }
}
