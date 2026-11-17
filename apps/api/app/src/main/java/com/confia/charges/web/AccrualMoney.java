package com.confia.charges.web;

import com.confia.kernel.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.RoundingMode;

/**
 * An amount as it travels over the API, {@code { amount: string, currency: string }}: never a JSON number. The amount is
 * rounded by the server to the minor unit of the currency (two decimals for HNL and USD) and the browser only formats it.
 */
@Schema(name = "AccrualMoney", description = "A monetary amount as it travels over the API: never a JSON number")
public record AccrualMoney(
        @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^-?\\d{1,10}(\\.\\d{1,4})?$",
                description = "Decimal amount in the minor unit of the currency, as a string") String amount,
        @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[A-Z]{3}$",
                description = "ISO 4217 currency code") String currency) {

    static AccrualMoney of(Money money) {
        if (money == null) {
            return null;
        }
        Money presented = money.roundToMinorUnit(RoundingMode.HALF_UP);
        return new AccrualMoney(presented.amount().setScale(money.currency().minorUnitDigits()).toPlainString(),
                money.currency().name());
    }
}
