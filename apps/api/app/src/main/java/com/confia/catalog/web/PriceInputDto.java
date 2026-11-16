package com.confia.catalog.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The price of a request, {@code { amount: string, currency: string }}. Unlike the general {@code Money} of a response
 * it carries no sign and at most two decimals (the minor unit of {@code HNL} and {@code USD}), so the price stored is the
 * price shown; the sign and the zero are judged by the domain as well.
 */
@Schema(name = "PriceInput", description = "A price as it is sent to the API: positive, at most two decimals, as a string")
public record PriceInputDto(
        @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^\\d{1,10}(\\.\\d{1,2})?$",
                description = "Decimal amount greater than zero with up to two decimals, as a string")
        @NotNull @Pattern(regexp = "^\\d{1,10}(\\.\\d{1,2})?$") @JsonDeserialize(using = StrictTextDeserializer.class)
        String amount,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"HNL", "USD"}, description = "ISO 4217 currency code")
        @NotNull @Pattern(regexp = "HNL|USD") String currency) {
}
