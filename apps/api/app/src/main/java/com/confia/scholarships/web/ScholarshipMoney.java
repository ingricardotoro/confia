package com.confia.scholarships.web;

import com.confia.kernel.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.RoundingMode;

/**
 * An amount of a scholarship as it travels over the API, {@code { amount: string, currency: string }}: never a JSON number. The
 * amount is rounded by the server to the minor unit of the currency (two decimals for HNL) and the browser only formats it. As input,
 * the currency is HNL (the scholarships of this cut are in the currency of the institution), the amount has at most two decimals;
 * the domain checks both again and refuses zero.
 */
@Schema(name = "ScholarshipMoney", description = "A monetary amount as it travels over the API: never a JSON number")
public record ScholarshipMoney(
        @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^\\d{1,10}(\\.\\d{1,2})?$",
                description = "Decimal amount in the minor unit of the currency, as a string")
        @NotNull @Pattern(regexp = "^\\d{1,10}(\\.\\d{1,2})?$") String amount,
        @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^HNL$", description = "ISO 4217 currency code of the institution")
        @NotNull @Pattern(regexp = "^HNL$") String currency) {

    static ScholarshipMoney of(Money money) {
        if (money == null) {
            return null;
        }
        Money presented = money.roundToMinorUnit(RoundingMode.HALF_UP);
        return new ScholarshipMoney(presented.amount().setScale(money.currency().minorUnitDigits()).toPlainString(),
                money.currency().name());
    }
}
