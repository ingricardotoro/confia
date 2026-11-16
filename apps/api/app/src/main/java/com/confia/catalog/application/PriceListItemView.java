package com.confia.catalog.application;

import com.confia.catalog.domain.PriceListItem;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A price as the web layer may see it. The amount is the one of presentation: rounded by the server to the minor unit of
 * its currency (two decimals for {@code HNL} and {@code USD}) with {@link RoundingMode#HALF_UP}, as a string, so no
 * client calculates or rounds money.
 */
public record PriceListItemView(UUID id, UUID priceListId, UUID paymentConceptId, String amount, String currency,
        LocalDate validFrom, LocalDate validTo, Instant createdAt) {

    static PriceListItemView of(PriceListItem item) {
        int digits = item.price().currency().minorUnitDigits();
        String amount = item.price().roundToMinorUnit(RoundingMode.HALF_UP).amount()
                .setScale(digits, RoundingMode.UNNECESSARY).toPlainString();
        return new PriceListItemView(item.id(), item.priceListId(), item.paymentConceptId(), amount,
                item.price().currency().name(), item.validFrom(), item.validTo(), item.createdAt());
    }
}
