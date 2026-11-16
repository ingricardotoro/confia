package com.confia.catalog.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * An amount as it travels over the API, {@code { amount: string, currency: string }}: never a JSON number. It is the
 * {@code Money} schema the contract already fixes. As an input, the amount is positive-looking text with up to ten
 * integer digits and four decimals and the currency is one the system operates with; the sign and the zero are judged by
 * the domain. As an output, the amount is already rounded by the server to the minor unit of the currency.
 */
@Schema(name = "Money", description = "A monetary amount as it travels over the API: never a JSON number")
public record MoneyDto(
        @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^-?\\d{1,10}(\\.\\d{1,4})?$",
                description = "Decimal amount with up to four decimals, as a string")
        @NotNull @Pattern(regexp = "^\\d{1,10}(\\.\\d{1,4})?$") @JsonDeserialize(using = StrictTextDeserializer.class)
        String amount,
        @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[A-Z]{3}$",
                description = "ISO 4217 currency code")
        @NotNull @Pattern(regexp = "HNL|USD") String currency) {
}
