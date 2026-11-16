package com.confia.shared.infrastructure.money;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The one place that turns the pair of columns {@code NUMERIC(14,4)} and {@code CHAR(3)} currency into a {@link Money}
 * and back (ADR-0015, rule 5; ADR-0004). No repository builds or takes apart an amount by itself. The amount read from
 * the database goes through {@link Money#of(BigDecimal, CurrencyCode)}, so it is normalized to scale four and bounded;
 * a currency outside the enabled set fails with the kernel error instead of becoming a string nobody checks.
 */
public final class MoneyColumns {

    private MoneyColumns() {
    }

    /** The amount of the two columns; neither may be {@code null}. */
    public static Money toMoney(BigDecimal amount, String currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        return Money.of(amount, CurrencyCode.fromIsoCode(currency));
    }

    /** The value for the {@code NUMERIC(14,4)} column, at scale four. */
    public static BigDecimal amountColumn(Money money) {
        return Objects.requireNonNull(money, "money").amount();
    }

    /** The value for the currency column. */
    public static String currencyColumn(Money money) {
        return Objects.requireNonNull(money, "money").currency().name();
    }
}
