package com.confia.scholarships.infrastructure;

import com.confia.kernel.Percentage;
import com.confia.scholarships.domain.Benefit;
import com.confia.scholarships.domain.BenefitType;
import com.confia.shared.infrastructure.money.MoneyColumns;
import java.math.BigDecimal;

/** How a {@link Benefit} crosses to the type, percentage and fixed amount columns and back; amounts go through {@link MoneyColumns}. */
final class BenefitColumns {

    private BenefitColumns() {
    }

    static String type(Benefit benefit) {
        return benefit == null ? null : benefit.type().name();
    }

    static BigDecimal percent(Benefit benefit) {
        return benefit == null || benefit.percent() == null ? null : benefit.percent().value();
    }

    static BigDecimal fixedAmount(Benefit benefit) {
        return benefit == null || benefit.fixedAmount() == null ? null : MoneyColumns.amountColumn(benefit.fixedAmount());
    }

    /** The benefit of the three columns, or {@code null} when the type is absent (a snapshot that was never kept). */
    static Benefit read(String type, BigDecimal percent, BigDecimal fixedAmount, String currency) {
        if (type == null) {
            return null;
        }
        BenefitType benefitType = BenefitType.valueOf(type);
        return benefitType == BenefitType.PERCENTAGE ? new Benefit(benefitType, Percentage.of(percent), null)
                : new Benefit(benefitType, null, MoneyColumns.toMoney(fixedAmount, currency));
    }
}
